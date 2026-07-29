"""
Generator wariantow sezonowych archetypow.

Wczytuje 6 baseline archetypow z base/ i tworzy 24 warianty w seasonal/
(6 archetypow x 4 sezony).

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

# ---- Definicja modyfikatorow sezonowych ----

def apply_winter(config: dict) -> dict:
    """Zima: HEATER + wiecej bojler, dluzsze swiatlo."""
    config = copy.deepcopy(config)
    config["archetype_name"] += " - Zima"
    config["archetype_description"] = "[ZIMA] " + config["archetype_description"] + \
        " Dodano: grzejnik elektryczny 2000W rano (6-8) i wieczorem (17-23), zwiekszony bojler, dluzsze oswietlenie o zmierzchu."

    # Zwieksz bojler duty (zimna woda)
    for d in config["devices"]:
        if d["type"] == "BOILER":
            d["params"]["duty_cycle"] = min(0.35, d["params"].get("duty_cycle", 0.10) + 0.15)

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

    # Przesun swiatla o 30 min wczesniej (ciemniej)
    for d in config["devices"]:
        if d["type"] == "LIGHT" and isinstance(d.get("schedule"), list):
            for event in d["schedule"]:
                if event["action"] == "ON":
                    hh, mm = map(int, event["at"].split(":"))
                    # rano wlacz 30 min wczesniej, wieczorem 30 min wczesniej
                    if hh >= 14:  # wieczorne
                        hh = max(0, hh - 1)
                        event["at"] = f"{hh:02d}:{mm:02d}"

    return config


def apply_spring(config: dict) -> dict:
    """Wiosna: baseline. Bez zmian klimatycznych."""
    config = copy.deepcopy(config)
    config["archetype_name"] += " - Wiosna"
    config["archetype_description"] = "[WIOSNA] " + config["archetype_description"] + \
        " Bez ogrzewania i klimatyzacji - laczne zuzycie referencyjne."
    return config


def apply_summer(config: dict) -> dict:
    """Lato: AC + wieksza lodowka, krotsze swiatlo."""
    config = copy.deepcopy(config)
    config["archetype_name"] += " - Lato"
    config["archetype_description"] = "[LATO] " + config["archetype_description"] + \
        " Dodano: klimatyzacja 1200W w godzinach 12-22, zwiekszony duty lodowki (upal), skrocone oswietlenie."

    # Zwieksz duty lodowki (upal - czesciej wlacza sie kompresor)
    for d in config["devices"]:
        if d["type"] == "REFRIGERATOR":
            d["params"]["duty_cycle"] = min(0.60, d["params"].get("duty_cycle", 0.35) + 0.15)

    # Dodaj AC w salonie i sypialni
    config["devices"].append({
        "id": "ac_livingroom",
        "room": "LIVING_ROOM",
        "type": "AC",
        "params": {"power_w": 1200, "duty_cycle": 0.55, "cycle_length_minutes": 30},
        "schedule": [
            {"at": "12:00", "action": "ON"},
            {"at": "23:00", "action": "OFF"}
        ]
    })
    config["devices"].append({
        "id": "ac_bedroom",
        "room": "BEDROOM",
        "type": "AC",
        "params": {"power_w": 900, "duty_cycle": 0.40, "cycle_length_minutes": 30},
        "schedule": [
            {"at": "22:00", "action": "ON"},
            {"at": "06:00", "action": "OFF"}
        ]
    })

    # Skroc swiatla o 30 min (jasno dluzej)
    for d in config["devices"]:
        if d["type"] == "LIGHT" and isinstance(d.get("schedule"), list):
            for event in d["schedule"]:
                if event["action"] == "ON":
                    hh, mm = map(int, event["at"].split(":"))
                    if hh >= 14:  # wieczorne wlacz 30 min pozniej
                        hh = min(23, hh + 1)
                        event["at"] = f"{hh:02d}:{mm:02d}"

    return config


def apply_autumn(config: dict) -> dict:
    """Jesien: lekki HEATER wieczorem, lekko wieksze swiatlo."""
    config = copy.deepcopy(config)
    config["archetype_name"] += " - Jesien"
    config["archetype_description"] = "[JESIEN] " + config["archetype_description"] + \
        " Dodano: lekki grzejnik 1000W tylko wieczorem (18-22), lekko wieksze oswietlenie."

    # Lekko wiekszy bojler
    for d in config["devices"]:
        if d["type"] == "BOILER":
            d["params"]["duty_cycle"] = min(0.22, d["params"].get("duty_cycle", 0.10) + 0.05)

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
        print(f"[BLAD] Brak baseline archetypow w {BASE_DIR}")
        return

    total_generated = 0

    for base_file in base_files:
        with open(base_file, "r", encoding="utf-8") as f:
            base_config = json.load(f)

        code = base_config.get("archetype_code", "?")
        name = base_config.get("archetype_name", base_file.stem)
        print(f"[{code}] {name}")

        for season, modifier in SEASON_MODIFIERS.items():
            seasonal_config = modifier(base_config)

            # name pola TestConfig (backend wymaga)
            seasonal_config["name"] = f"Archetyp {code}: {name} - {season.capitalize()}"
            seasonal_config["description"] = seasonal_config["archetype_description"]

            # Usun pola pomocnicze (backend ich nie potrzebuje, TestConfig
            # ma @JsonIgnoreProperties(ignoreUnknown = true) wiec i tak przejdzie,
            # ale porzadkujemy).
            for k in ("archetype_code", "archetype_name", "archetype_description"):
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
