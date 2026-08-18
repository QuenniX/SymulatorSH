"""
Analityczny kontroler zuzycia dobowego profili.

Liczy oczekiwane kWh/dobe WPROST z konfiguracji JSON, bez uruchamiania symulacji.
Sluzy do walidacji potoku pomiarowego: po partii testow porownaj te liczby z
sum(E_h) odczytanym z API (albo z K_G11 / (30 * 1.10) z tabeli wynikow).

Rozbieznosc > ~3% oznacza blad w potoku (aliasing probkowania, gubione pomiary
MQTT/InfluxDB, obciete okno `range` w zapytaniu Flux), a nie w konfiguracji.

Dodatkowo raportuje udzial strefy nocnej G12 ({22,23,0..5,13,14}) - przy progu
oplacalnosci G12 vs G11 rownym 23,8% ta kolumna tlumaczy caly wynik G12.

Uruchomienie:
    python oczekiwane_kwh.py
"""
import json
from pathlib import Path

SEASONS = ["zima", "wiosna", "lato", "jesien"]
G12_NIGHT = {22, 23, 0, 1, 2, 3, 4, 5, 13, 14}
STATE_DEVICES = {"LIGHT", "TV", "HEATER", "ROUTER", "AC", "COMPUTER"}


def to_min(t):
    h, m = map(int, t.split(":"))
    return h * 60 + m


def on_minutes_per_hour(dev):
    """Zwraca 24-elementowa liste minut w stanie ON, wg tej samej semantyki
    co stateTimeline.floorEntry w LightSimulator (domyslnie OFF od minuty 0)."""
    sched = dev.get("schedule")
    per_hour = [0] * 24
    if sched == "always_on":
        return [60] * 24
    if sched == "always_off" or not isinstance(sched, list):
        return per_hour
    timeline = {0: False}
    for ev in sched:
        timeline[to_min(ev["at"])] = ev.get("action", "ON").upper() == "ON"
    keys = sorted(timeline)
    state, ki = False, 0
    for minute in range(1440):
        while ki < len(keys) and keys[ki] <= minute:
            state = timeline[keys[ki]]
            ki += 1
        if state:
            per_hour[minute // 60] += 1
    return per_hour


def device_kwh_per_hour(dev):
    """24-elementowa lista kWh zuzytych w kazdej godzinie doby."""
    t, p = dev["type"], dev.get("params", {})
    oh = on_minutes_per_hour(dev)

    if t in ("BOILER", "REFRIGERATOR", "AC"):
        cycle = p.get("cycle_length_minutes", 43)
        duty_eff = round(cycle * p.get("duty_cycle", 0.4)) / cycle
        w = p.get("power_w", 0)
        return [w * (mins / 60) * duty_eff / 1000 for mins in oh]

    if t == "COMPUTER":
        L, I = p.get("burst_length_minutes", 5), p.get("burst_interval_minutes", 20)
        duty = L / (L + (I - 1) / 2)
        w = duty * p.get("burst_power_w", 300) + (1 - duty) * p.get("idle_power_w", 100)
        return [w * (mins / 60) / 1000 for mins in oh]

    if t in STATE_DEVICES:
        w = p.get("power_w", 0)
        return [w * (mins / 60) / 1000 for mins in oh]

    # Urzadzenia zdarzeniowe: kazdy ON startuje cykl o stalej dlugosci.
    out = [0.0] * 24
    for ev in dev.get("schedule", []) if isinstance(dev.get("schedule"), list) else []:
        if ev.get("action", "ON").upper() != "ON":
            continue
        start = to_min(ev["at"])
        if t == "DISHWASHER":
            phases = [(p.get("heat_phase_minutes", 10), p.get("heat_power_w", 1800)),
                      (p.get("wash_phase_minutes", 60), p.get("wash_power_w", 200)),
                      (p.get("dry_phase_minutes", 20), p.get("dry_power_w", 1500))]
        elif t == "OVEN":
            on_m, off_m = p.get("heat_on_minutes", 5), p.get("heat_off_minutes", 3)
            phases = [(p.get("cycle_minutes", 60),
                       p.get("power_w", 2500) * on_m / (on_m + off_m))]
        else:  # KETTLE, WASHER
            phases = [(p.get("cycle_minutes", 3 if t == "KETTLE" else 60),
                       p.get("power_w", 2000 if t == "KETTLE" else 2000))]
        cursor = start
        for dur, w in phases:
            for i in range(int(dur)):
                minute = cursor + i
                if minute >= 1440:      # cykl uciety o polnocy (jak w symulatorze)
                    break
                out[minute // 60] += w / 60 / 1000
            cursor += int(dur)
    return out


def main():
    base = Path(__file__).parent / "seasonal"
    print(f"{'Profil':26s} " + "".join(f"{s.capitalize():>10s}" for s in SEASONS)
          + "   udzial nocny G12 (zima/wiosna/lato/jesien)")
    print("-" * 118)
    for f in sorted(base.glob("*_zima.json")):
        stem = f.name[:-10]
        kwhs, nights, label = [], [], stem
        for s in SEASONS:
            cfg = json.load(open(base / f"{stem}_{s}.json", encoding="utf-8"))
            # etykieta z pola "name" (np. "Profil B: Pracownik zdalny - Zima"),
            # zeby raport nie pokazywal nazw plikow
            label = cfg.get("name", stem).split(" - ")[0]
            per_hour = [0.0] * 24
            for dev in cfg["devices"]:
                for h, v in enumerate(device_kwh_per_hour(dev)):
                    per_hour[h] += v
            total = sum(per_hour)
            kwhs.append(total)
            nights.append(sum(per_hour[h] for h in G12_NIGHT) / total if total else 0)
        print(f"{label:26s} " + "".join(f"{k:9.1f} " for k in kwhs)
              + "   " + "  ".join(f"{n:5.1%}" for n in nights))
    print()
    print("Prog oplacalnosci G12 vs G11 przy stawkach 2026: 23,8% zuzycia w strefie nocnej.")
    print("Kolumna 'udzial nocny' powyzej progu => G12 tansza od G11 z samej arytmetyki.")


if __name__ == "__main__":
    main()
