"""
Analiza wrazliwosci wynikow na zalozenie o sezonowym zwiekszeniu poboru bojlera.

PROBLEM
  generate_seasonal.py zwieksza duty cycle bojlera ADDYTYWNIE: +0,15 zima, +0,05 jesien.
  Dla profilu o duty bazowym 0,10 oznacza to wzrost o 150%, podczas gdy fizyczne
  uzasadnienie (zimniejsza woda wodociagowa) daje 25-40%. Bojler jest obciazeniem
  plaskim przez cala dobe, wiec jego zawyzenie podnosi udzial strefy nocnej G12,
  a tym samym zawyza przewage taryfy dwustrefowej.

CO LICZY
  Ten sam profil dobowy przy czterech wariantach modyfikatora sezonowego bojlera,
  zestawiony z rzeczywistymi cenami RDN 2025. Pokazuje, o ile wynik zalezy od
  spornego zalozenia - zamiast po cichu przyjmowac lepsze parametry.

  Wariant "obecny" odtwarza konfiguracje uzyta w partii testow (walidacja: udzialy
  nocne musza sie zgadzac z tabela z oczekiwane_kwh.py).

UWAGA METODOLOGICZNA - INNE ZRODLO PROFILU NIZ POZOSTALE SKRYPTY
  Ten skrypt musi modyfikowac duty cycle bojlera, wiec profil dobowy liczy
  ANALITYCZNIE z konfiguracji JSON. Pozostale analizy (analiza_wyniki.py,
  demand_response_zmierzone.py, wrazliwosc_g12.py) licza go ze ZMIERZONYCH danych
  symulacji. Roznica miedzy modelem a pomiarem siega kilku procent zuzycia
  dobowego, co przy przypadkach lezacych blisko progu tau potrafi przestawic je
  na druga strone.

  W konsekwencji liczby z tego skryptu sluza WYLACZNIE do porownan MIEDZY
  WARIANTAMI modyfikatora (o ile zmienia sie wynik, gdy zmienimy zalozenie).
  Nie wolno ich cytowac jako rozstrzygniecia dla pojedynczych przypadkow -
  do tego sluzy tabela_zbiorcza.md, oparta na pomiarze.

WEJSCIE   archetypes/base/*.json, archetypes/seasonal/*.json, analiza/ceny_sezony_2025.csv
"""
import csv
import json
from collections import defaultdict
from pathlib import Path

TU = Path(__file__).resolve().parent
BASE = TU.parent / "archetypes" / "base"
SEAS = TU.parent / "archetypes" / "seasonal"
# zrodlo cen: zrzut z eksport_danych.py (zwalidowany 4 x 30 x 24), z fallbackiem
CENY = TU / "dane_zrodlowe" / "ceny" / "wszystkie.csv"
if not CENY.exists():
    CENY = TU / "ceny_sezony_2025.csv"

# parametry taryfowe: modul taryfy.py (jedno zrodlo prawdy)
import taryfy as T

SEZONY = T.SEZONY
G11, G12_D, G12_N = T.G11, T.G12_DZIEN, T.G12_NOC
PROG_NOCNY = T.prog_udzialu_nocnego()             # 0,2350

# warianty modyfikatora: (etykieta, funkcja(base_duty, sezon) -> duty)
WARIANTY = [
    ("obecny (addytywny)", lambda b, s: min(0.35, b + 0.15) if s == "Zima"
                                   else min(0.22, b + 0.05) if s == "Jesien" else b),
    ("x1,40",              lambda b, s: b * 1.40 if s == "Zima" else b * 1.15 if s == "Jesien" else b),
    ("x1,35",              lambda b, s: b * 1.35 if s == "Zima" else b * 1.12 if s == "Jesien" else b),
    ("x1,25",              lambda b, s: b * 1.25 if s == "Zima" else b * 1.08 if s == "Jesien" else b),
    ("bez modyfikatora",   lambda b, s: b),
]

STANOWE = {"LIGHT", "TV", "HEATER", "ROUTER", "AC", "COMPUTER"}


def _m(t):
    h, mi = t.split(":")
    return int(h) * 60 + int(mi)


def minuty_on(dev):
    s = dev.get("schedule")
    per = [0] * 24
    if s == "always_on":
        return [60] * 24
    if s == "always_off" or not isinstance(s, list):
        return per
    tl = {0: False}
    for ev in s:
        tl[_m(ev["at"])] = ev.get("action", "ON").upper() == "ON"
    ks = sorted(tl)
    st, i = False, 0
    for mi in range(1440):
        while i < len(ks) and ks[i] <= mi:
            st = tl[ks[i]]
            i += 1
        if st:
            per[mi // 60] += 1
    return per


def kwh_godzinowe(dev, nadpisz_duty=None):
    t, p = dev["type"], dev.get("params", {})
    oh = minuty_on(dev)
    if t in ("BOILER", "REFRIGERATOR", "AC"):
        cyc = p.get("cycle_length_minutes", 43)
        duty = nadpisz_duty if (t == "BOILER" and nadpisz_duty is not None) else p.get("duty_cycle", 0.4)
        duty_eff = round(cyc * min(0.95, max(0.05, duty))) / cyc
        return [p.get("power_w", 0) * (m / 60) * duty_eff / 1000 for m in oh]
    if t == "COMPUTER":
        L, I = p.get("burst_length_minutes", 5), p.get("burst_interval_minutes", 20)
        d = L / (L + (I - 1) / 2)
        w = d * p.get("burst_power_w", 300) + (1 - d) * p.get("idle_power_w", 100)
        return [w * (m / 60) / 1000 for m in oh]
    if t in STANOWE:
        return [p.get("power_w", 0) * (m / 60) / 1000 for m in oh]

    out = [0.0] * 24
    for ev in (dev.get("schedule") if isinstance(dev.get("schedule"), list) else []):
        if ev.get("action", "ON").upper() != "ON":
            continue
        start = _m(ev["at"])
        if t == "DISHWASHER":
            fazy = [(p.get("heat_phase_minutes", 10), p.get("heat_power_w", 1800)),
                    (p.get("wash_phase_minutes", 60), p.get("wash_power_w", 200)),
                    (p.get("dry_phase_minutes", 20), p.get("dry_power_w", 1500))]
        elif t == "OVEN":
            on_m, off_m = p.get("heat_on_minutes", 5), p.get("heat_off_minutes", 3)
            fazy = [(p.get("cycle_minutes", 60), p.get("power_w", 2500) * on_m / (on_m + off_m))]
        else:
            fazy = [(p.get("cycle_minutes", 3 if t == "KETTLE" else 60), p.get("power_w", 2000))]
        kur = start
        for dur, w in fazy:
            for i in range(int(dur)):
                if kur + i >= 1440:
                    break
                out[(kur + i) // 60] += w / 60 / 1000
            kur += int(dur)
    return out


def main():
    if not CENY.exists():
        print(f"[BLAD] brak {CENY}")
        return

    ceny = defaultdict(lambda: defaultdict(dict))
    with open(CENY, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            c = float(str(r["cena_pln_mwh"]).replace(",", "."))
            ceny[r["sezon"]][str(r["data"])[:10]][int(r["godz"])] = c

    # duty bazowe bojlera per profil
    baza = {}
    for f in sorted(BASE.glob("*.json")):
        d = json.load(open(f, encoding="utf-8"))
        for dev in d["devices"]:
            if dev["type"] == "BOILER":
                baza[d["profile_code"]] = dev["params"].get("duty_cycle", 0.10)

    pliki = {}
    for f in sorted(SEAS.glob("*.json")):
        d = json.load(open(f, encoding="utf-8"))
        kod = d["name"].split(":")[0].replace("Profil", "").strip()
        sez = d["name"].split(" - ")[-1].strip()
        pliki[(kod, sez)] = d

    print()
    print(f"Prog oplacalnosci G12 vs G11: {PROG_NOCNY:.1%} zuzycia w strefie nocnej")
    print()

    wyniki = {}
    for etykieta, mod in WARIANTY:
        wiersze = []
        for kod in "ABCDEF":
            for sez in SEZONY:
                cfg = pliki[(kod, sez)]
                duty = mod(baza[kod], sez)
                prof = [0.0] * 24
                boj = [0.0] * 24
                for dev in cfg["devices"]:
                    wek = kwh_godzinowe(dev, nadpisz_duty=duty)
                    for h in range(24):
                        prof[h] += wek[h]
                    if dev["type"] == "BOILER":
                        for h in range(24):
                            boj[h] += wek[h]
                suma = sum(prof)
                noc = T.udzial_nocny(prof, sez) if suma else 0

                kg11 = kg12 = krdn = 0.0
                for _, godz in sorted(ceny[sez].items()):
                    if len(godz) != 24:
                        continue
                    for h in range(24):
                        kg11 += prof[h] * G11
                        kg12 += prof[h] * T.g12_cena(h, sez)
                        krdn += prof[h] * T.rdn_detal(godz[h])
                wiersze.append(dict(kod=kod, sez=sez, kwh=suma, noc=noc, bojler=sum(boj),
                                    g11=kg11, g12=kg12, rdn=krdn))
        wyniki[etykieta] = wiersze

    # --- tabela udzialu nocnego ---
    print("UDZIAL STREFY NOCNEJ [%] - pogrubione ponizej progu (G11 tansza od G12)")
    print()
    hdr = f"{'Wariant':22s}" + "".join(f"{k+'-'+s[:3]:>10s}" for k in "ABCDEF" for s in ["Zima", "Jesien"])
    print(hdr)
    print("-" * len(hdr))
    for etykieta, w in wyniki.items():
        idx = {(r["kod"], r["sez"]): r for r in w}
        linia = f"{etykieta:22s}"
        for k in "ABCDEF":
            for s in ["Zima", "Jesien"]:
                v = idx[(k, s)]["noc"]
                linia += f"{v:9.1%}{'*' if v < PROG_NOCNY else ' '}"
        print(linia)
    print(f"   (* = ponizej progu {PROG_NOCNY:.1%})")
    print("   UWAGA: udzialy powyzej pochodza z MODELU ANALITYCZNEGO, nie z pomiaru.")
    print("   Sluza do porownan miedzy wariantami; rozstrzygniecia dla pojedynczych")
    print("   przypadkow podaje tabela_zbiorcza.md, liczona na zmierzonych profilach.")

    # --- agregaty ---
    print()
    print("AGREGATY (24 przypadki diagonalne)")
    print()
    hdr2 = (f"{'Wariant':22s} {'kWh/dobe sr.':>13s} {'bojler %':>9s} "
            f"{'RDN vs G11':>11s} {'RDN vs G12':>11s} {'G12 vs G11':>11s} {'G11>G12 (model)':>16s}")
    print(hdr2)
    print("-" * len(hdr2))
    for etykieta, w in wyniki.items():
        n = len(w)
        kwh = sum(r["kwh"] for r in w) / n
        udz_boj = sum(r["bojler"] for r in w) / sum(r["kwh"] for r in w)
        sg11 = sum(r["g11"] for r in w)
        sg12 = sum(r["g12"] for r in w)
        srdn = sum(r["rdn"] for r in w)
        ile = sum(1 for r in w if r["g12"] > r["g11"])
        print(f"{etykieta:22s} {kwh:13.1f} {udz_boj:8.1%} "
              f"{(sg11-srdn)/sg11:10.2%} {(sg12-srdn)/sg12:10.2%} {(sg11-sg12)/sg11:10.2%} {ile:13d}/24")
    print()
    print("   Kolumna 'G11>G12 (model)' liczona na profilach analitycznych i sluzy tylko")
    print("   do sledzenia kierunku zmiany miedzy wariantami. Na zmierzonych profilach")
    print("   (tabela_zbiorcza.md) liczba tych przypadkow jest inna - to nie jest sprzecznosc,")
    print("   tylko roznica zrodla danych.")


if __name__ == "__main__":
    main()
