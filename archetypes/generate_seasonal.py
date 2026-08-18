"""
Generator wariantow sezonowych profilow.

Wczytuje 6 baseline profilow z base/ i tworzy 24 warianty w seasonal/
(6 profilow x 4 sezony).

Modyfikacje per sezon:
  - Zima:   dodaje HEATER 1500-2500W (wieczorami + rano), zwieksza bojler duty,
            dluzsze oswietlenie (ciemno wczesnie)
  - Wiosna: baseline bez zmian (bez HEATER, bez AC)
  - Lato:   dodaje AC 1000-1500W (godziny 12-22), zwieksza lodowki duty (upal),
            skraca oswietlenie (jasno dlugo)
  - Jesien: lekki HEATER 1000W (tylko wieczorem 18-22), lekko wieksze oswietlenie

Uruchomienie:
    python generate_seasonal.py
"""
import json
import copy
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
BASE_DIR = SCRIPT_DIR / "base"
OUT_DIR = SCRIPT_DIR / "seasonal"

# ---- Helpery czasowe ----

# Urzadzenia STANOWE: schedule buduje TreeMap stan-w-czasie (ON trwa az do OFF).
# Tylko one wymagaja naprzemiennosci ON/OFF i tylko u nich para przechodzaca
# przez polnoc jest gubiona przez `stateTimeline.put(0, false)`.
STATE_DEVICES = {"LIGHT", "TV", "HEATER", "ROUTER", "AC", "COMPUTER"}

# Urzadzenia ZDARZENIOWE: ON startuje cykl o stalej dlugosci, ktory konczy sie sam.
# Dwa ON pod rzad (np. czajnik 07:00 i 19:00) sa tu poprawne.
EVENT_DEVICES = {"KETTLE", "WASHER", "DISHWASHER", "OVEN"}


def _to_min(t: str) -> int:
    h, m = map(int, t.split(":"))
    return h * 60 + m


def _to_str(m: int) -> str:
    return f"{m // 60:02d}:{m % 60:02d}"


def shift_evening_on(config: dict, delta_min: int, min_gap: int = 15) -> None:
    """
    Przesuwa wieczorne zdarzenia ON swiatel o delta_min, NIE przekraczajac sparowanego OFF.

    BUG, ktory to naprawia: poprzednia wersja przesuwala wylacznie zdarzenia ON i nie
    patrzyla na OFF. Para (ON 18:45 / OFF 19:15) po przesunieciu o +1h stawala sie
    (ON 19:45 / OFF 19:15) - OFF PRZED ON. LightSimulator buduje TreeMap po minucie,
    wiec swiatlo zapalalo sie o 19:45 i nie gaslo do polnocy (3h15 zamiast 30 min),
    czyli dokladnie w wieczornym szczycie cen RDN. Para (ON 22:30 / OFF 23:30) dawala
    (ON 23:30 / OFF 23:30) - ta sama minuta, `put` nadpisywal ON przez OFF i wieczorne
    swiatlo nie zapalalo sie w ogole.
    """
    for d in config["devices"]:
        if d.get("type") != "LIGHT" or not isinstance(d.get("schedule"), list):
            continue
        sched = d["schedule"]
        for i, ev in enumerate(sched):
            if ev.get("action") != "ON":
                continue
            cur = _to_min(ev["at"])
            if cur < 14 * 60:          # tylko wieczorne
                continue
            new = max(0, min(23 * 60 + 59, cur + delta_min))

            if delta_min > 0:
                # nie wolno wejsc na (ani za) najblizszy pozniejszy OFF tej samej doby
                nxt = next((_to_min(e["at"]) for e in sched[i + 1:]
                            if e.get("action") == "OFF" and _to_min(e["at"]) > cur), None)
                if nxt is not None:
                    new = min(new, nxt - min_gap)
                new = max(new, cur)     # clamp nie moze cofnac przesuniecia w tyl
            else:
                # nie wolno cofnac sie przed najblizszy wczesniejszy OFF
                prv = max((_to_min(e["at"]) for e in sched[:i]
                           if e.get("action") == "OFF" and _to_min(e["at"]) < cur), default=None)
                if prv is not None:
                    new = max(new, prv + min_gap)
                new = min(new, cur)
            ev["at"] = _to_str(new)


def split_overnight(config: dict) -> None:
    """
    Rozbija pary ON->OFF przechodzace przez polnoc na dwa odcinki tej samej doby.

    BUG, ktory to naprawia: LightSimulator/AcSimulator/ComputerSimulator buduja
    `stateTimeline` na JEDNA dobe i zaczynaja od `put(0, false)`. Para
    (ON 22:00 -> OFF 06:00) dawala mape {0:false, 360:false, 1320:true}, czyli
    urzadzenie dzialalo 2h z 8h - odcinek 00:00-06:00 przepadal. Dotyczylo to
    nocnej klimatyzacji we WSZYSTKICH profilach letnich oraz calego profilu E
    (studenci: computer 20->02, tv 19->02, salon 17->02, sypialnia 23->02:30),
    czyli ~1,7 kWh/dobe znikajace w calosci ze strefy nocnej G12.

    Pliki w base/ zapisuja INTENCJE w naturalnej notacji (22:00 -> 06:00);
    ta funkcja tlumaczy ja na postac wykonywalna przez symulator:
    [00:00 ON, 06:00 OFF, 22:00 ON]. Jitter na zdarzeniu o 00:00 jest zerowany,
    zeby nie przesunelo sie w glab doby.
    """
    for d in config["devices"]:
        sched = d.get("schedule")
        if not isinstance(sched, list) or not sched:
            continue
        if d.get("type") not in STATE_DEVICES:
            # Zdarzeniowe: kolejnosc w liscie nie ma znaczenia (userActions to TreeMap
            # po minucie), ale porzadkujemy dla czytelnosci plikow i walidacji.
            d["schedule"] = sorted(sched, key=lambda e: _to_min(e["at"]))
            continue
        crosses = False
        last_on = None
        for ev in sched:
            act = ev.get("action", "ON").upper()
            t = _to_min(ev["at"])
            if act == "ON":
                last_on = t
            elif act == "OFF" and last_on is not None and t < last_on:
                crosses = True
        if crosses and not any(_to_min(e["at"]) == 0 for e in sched):
            sched.insert(0, {"at": "00:00", "action": "ON", "jitter_time_minutes": 0})
        d["schedule"] = sorted(sched, key=lambda e: _to_min(e["at"]))


def validate_schedules(config: dict, label: str) -> None:
    """Twarda walidacja: rosnace czasy, brak duplikatow minut, naprzemienne ON/OFF."""
    for d in config["devices"]:
        sched = d.get("schedule")
        if not isinstance(sched, list) or not sched:
            continue
        times = [_to_min(e["at"]) for e in sched]
        if d.get("type") in STATE_DEVICES and times != sorted(times):
            raise AssertionError(f"{label}/{d['id']}: zdarzenia nieposortowane: {sched}")
        if len(set(times)) != len(times):
            raise AssertionError(f"{label}/{d['id']}: dwa zdarzenia w tej samej minucie: {sched}")
        # Naprzemiennosc ON/OFF ma sens tylko dla urzadzen stanowych.
        if d.get("type") in STATE_DEVICES:
            acts = [e.get("action", "ON").upper() for e in sched]
            for a, b in zip(acts, acts[1:]):
                if a == b:
                    raise AssertionError(f"{label}/{d['id']}: dwa {a} pod rzad: {sched}")


# ---- Definicja modyfikatorow sezonowych ----

def apply_winter(config: dict) -> dict:
    """Zima: HEATER + wiecej bojler, dluzsze swiatlo."""
    config = copy.deepcopy(config)
    config["profile_name"] += " - Zima"
    config["profile_description"] = "[ZIMA] " + config["profile_description"] + \
        " Dodano: grzejnik elektryczny 2000W rano (6-8) i wieczorem (17-23), zwiekszony bojler, dluzsze oswietlenie o zmierzchu."

    # Zwieksz bojler duty (zimniejsza woda wodociagowa)
    for d in config["devices"]:
        if d["type"] == "BOILER":
            base = d["params"].get("duty_cycle", 0.10)
            d["params"]["duty_cycle"] = round(max(base, min(0.35, base + 0.15)), 4)

    # Dodaj HEATER w salonie (wieczorami) i sypialni (rano+wieczor)
    config["devices"].append({
        "id": "heater_livingroom",
        "room": "LIVING_ROOM",
        "type": "HEATER",
        "params": {"power_w": 2000},
        "schedule": [
            {"at": "17:00", "action": "ON"},
            {"at": "23:00", "action": "OFF"}
        ]
    })
    config["devices"].append({
        "id": "heater_bedroom",
        "room": "BEDROOM",
        "type": "HEATER",
        "params": {"power_w": 1500},
        "schedule": [
            {"at": "06:00", "action": "ON"},
            {"at": "08:00", "action": "OFF"},
            {"at": "21:00", "action": "ON"},
            {"at": "23:30", "action": "OFF"}
        ]
    })

    # Wieczorne swiatlo zapala sie godzine wczesniej (wczesniejszy zmierzch).
    # Clamp pilnuje, zeby ON nie cofnal sie przed poprzedzajacy OFF.
    shift_evening_on(config, delta_min=-60)

    return config


def apply_spring(config: dict) -> dict:
    """Wiosna: baseline. Bez zmian klimatycznych."""
    config = copy.deepcopy(config)
    config["profile_name"] += " - Wiosna"
    config["profile_description"] = "[WIOSNA] " + config["profile_description"] + \
        " Bez ogrzewania i klimatyzacji - laczne zuzycie referencyjne."
    return config


def apply_summer(config: dict) -> dict:
    """Lato: AC + wieksza lodowka, krotsze swiatlo."""
    config = copy.deepcopy(config)
    config["profile_name"] += " - Lato"
    config["profile_description"] = "[LATO] " + config["profile_description"] + \
        " Dodano: klimatyzacja 1200W w godzinach 12-22, zwiekszony duty lodowki (upal), skrocone oswietlenie."

    # Zwieksz duty lodowki (upal - czesciej wlacza sie kompresor)
    for d in config["devices"]:
        if d["type"] == "REFRIGERATOR":
            base = d["params"].get("duty_cycle", 0.35)
            d["params"]["duty_cycle"] = round(max(base, min(0.60, base + 0.15)), 4)

    # Dodaj AC w salonie i sypialni
    config["devices"].append({
        "id": "ac_livingroom",
        "room": "LIVING_ROOM",
        "type": "AC",
        "params": {"power_w": 1200, "duty_cycle": 0.55, "cycle_length_minutes": 43},
        "schedule": [
            {"at": "12:00", "action": "ON"},
            {"at": "23:00", "action": "OFF"}
        ]
    })
    config["devices"].append({
        "id": "ac_bedroom",
        "room": "BEDROOM",
        "type": "AC",
        "params": {"power_w": 900, "duty_cycle": 0.40, "cycle_length_minutes": 43},
        "schedule": [
            {"at": "22:00", "action": "ON"},
            {"at": "06:00", "action": "OFF"}
        ]
    })

    # Wieczorne swiatlo zapala sie godzine pozniej (pozniejszy zmierzch).
    # Clamp pilnuje, zeby ON nie przeskoczyl sparowanego OFF - to byl bug, przez
    # ktory swiatlo w lazience palilo sie 19:45-23:00 zamiast 30 minut.
    shift_evening_on(config, delta_min=+60)

    return config


def apply_autumn(config: dict) -> dict:
    """Jesien: lekki HEATER wieczorem, lekko wieksze swiatlo."""
    config = copy.deepcopy(config)
    config["profile_name"] += " - Jesien"
    config["profile_description"] = "[JESIEN] " + config["profile_description"] + \
        " Dodano: lekki grzejnik 1000W tylko wieczorem (18-22), lekko wieksze oswietlenie."

    # Lekko wiekszy bojler. UWAGA: cap ma ograniczac WZROST, nie obnizac wartosc
    # bazowa - poprzednia wersja (`min(0.22, base+0.05)`) dawala dla profilu C
    # (base 0.25) wynik 0.22, czyli rodzina zuzywala jesienia MNIEJ CWU niz wiosna.
    for d in config["devices"]:
        if d["type"] == "BOILER":
            base = d["params"].get("duty_cycle", 0.10)
            d["params"]["duty_cycle"] = round(max(base, min(0.22, base + 0.05)), 4)

    # Dodaj lekki HEATER w salonie
    config["devices"].append({
        "id": "heater_livingroom",
        "room": "LIVING_ROOM",
        "type": "HEATER",
        "params": {"power_w": 1000},
        "schedule": [
            {"at": "18:00", "action": "ON"},
            {"at": "22:00", "action": "OFF"}
        ]
    })

    return config


SEASON_MODIFIERS = {
    "zima":   apply_winter,
    "wiosna": apply_spring,
    "lato":   apply_summer,
    "jesien": apply_autumn,
}


def main():
    OUT_DIR.mkdir(exist_ok=True)
    print(f"Generuje warianty sezonowe do {OUT_DIR}...")
    print()

    base_files = sorted(BASE_DIR.glob("*.json"))
    if not base_files:
        print(f"[BLAD] Brak baseline profilow w {BASE_DIR}")
        return

    total_generated = 0

    for base_file in base_files:
        with open(base_file, "r", encoding="utf-8") as f:
            base_config = json.load(f)

        code = base_config.get("profile_code", "?")
        name = base_config.get("profile_name", base_file.stem)
        print(f"[{code}] {name}")

        for season, modifier in SEASON_MODIFIERS.items():
            seasonal_config = modifier(base_config)

            # Normalizacja do postaci wykonywalnej przez symulator + twarda walidacja.
            # Kolejnosc ma znaczenie: najpierw przesuniecia sezonowe, potem rozbicie
            # polnocy, na koncu walidacja calosci.
            split_overnight(seasonal_config)
            validate_schedules(seasonal_config, f"{code}-{season}")

            # name pola TestConfig (backend wymaga)
            seasonal_config["name"] = f"Profil {code}: {name} - {season.capitalize()}"
            seasonal_config["description"] = seasonal_config["profile_description"]

            # Usun pola pomocnicze (backend ich nie potrzebuje, TestConfig
            # ma @JsonIgnoreProperties(ignoreUnknown = true) wiec i tak przejdzie,
            # ale porzadkujemy).
            for k in ("profile_code", "profile_name", "profile_description"):
                seasonal_config.pop(k, None)

            out_name = f"{code}_{base_file.stem[2:]}_{season}.json"
            out_path = OUT_DIR / out_name
            with open(out_path, "w", encoding="utf-8") as f:
                json.dump(seasonal_config, f, ensure_ascii=False, indent=2)
            print(f"    - {season:8s} -> {out_name}")
            total_generated += 1

    print()
    print(f"Wygenerowano {total_generated} wariantow sezonowych.")
    print(f"Uruchom teraz upload_templates.ps1 zeby wgrac je do bazy.")


if __name__ == "__main__":
    main()
