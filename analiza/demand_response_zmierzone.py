"""
Scenariusz aktywnego sterowania (demand response) - wariant B,
liczony na ZMIERZONYCH profilach dobowych z partii testow.

ROZNICA WOBEC demand_response.py
  Poprzednia wersja odtwarzala profil dobowy analitycznie z konfiguracji JSON.
  Ta wersja przyjmuje jako profil bazowy wektor E_h wyznaczony ze zmierzonych
  danych symulacji (analiza/dane_zrodlowe/profile/*.csv, 720 godzin na przypadek),
  czyli dokladnie ten sam wektor, na ktorym opiera sie rozdzial wynikowy.

DEKOMPOZYCJA OBCIAZENIA
  Zmierzony profil jest wielkoscia zagregowana - nie zawiera podzialu na
  urzadzenia. Skladnik elastyczny (pralka, zmywarka, bojler) odtworzono zatem
  z konfiguracji profilu i przeskalowano wspolczynnikiem k = E_zmierzone /
  E_analityczne, tak aby suma dobowa zgadzala sie ze zmierzona. Skladnik sztywny
  wyznaczono jako roznice:
        R_h = E_h^{zmierzone} - k * E_h^{elastyczne, analityczne}
  Ujemne wartosci R_h (zaokraglenia i drobne rozjazdy fazy) obcinane sa do zera,
  a odpowiadajaca im energia odejmowana od skladnika elastycznego. Skala tej
  korekty jest raportowana - jesli przekracza ~1% zuzycia dobowego, dekompozycja
  wymaga rewizji.

WEJSCIE
  analiza/dane_zrodlowe/profile/{LITERA}_{Sezon}.csv   - zmierzone profile
  analiza/dane_zrodlowe/ceny/wszystkie.csv             - ceny hurtowe RDN 2025
  archetypes/seasonal/*.json                           - konfiguracje (dekompozycja)

URUCHOMIENIE
  python demand_response_zmierzone.py
"""
import csv
import json
import math
import sys
from pathlib import Path
from collections import defaultdict

ROOT = Path(__file__).resolve().parent.parent
ANALIZA = Path(__file__).resolve().parent
SEASONAL = ROOT / "archetypes" / "seasonal"
PROFILE = ANALIZA / "dane_zrodlowe" / "profile"
CENY = ANALIZA / "dane_zrodlowe" / "ceny" / "wszystkie.csv"
WYNIKI = ANALIZA / "wyniki_analizy"

# --- parametry taryfowe 2026: modul taryfy.py (jedno zrodlo prawdy) ---
import taryfy as T

G11 = T.G11
VAT = T.VAT
SEZONY = T.SEZONY
ELASTYCZNE = {"WASHER", "DISHWASHER", "BOILER"}
STANOWE = {"LIGHT", "TV", "HEATER", "ROUTER", "AC", "COMPUTER"}

STEM = {"A": "A_singiel_biuro", "B": "B_remote_worker", "C": "C_rodzina_2_plus_2",
        "D": "D_senior_samotny", "E": "E_studenci", "F": "F_para_dink"}
ETYKIETA = {"A": "Singiel pracujacy", "B": "Pracownik zdalny", "C": "Rodzina 2+2",
            "D": "Senior samotny", "E": "Studenci", "F": "Para bez dzieci"}

# ograniczenia komfortu (wariant B)
DEADLINE_AGD = 7                 # pralka/zmywarka gotowe do 07:00
OKNA_CWU = [(4, 7), (16, 19)]    # bojler: min. 1 h grzania w kazdym oknie


def rdn_detal(cena_hurt_mwh):
    return T.rdn_detal(cena_hurt_mwh)


def g12_cena(h, sezon):
    """Strefy G12 sa sezonowe - patrz taryfy.py."""
    return T.g12_cena(h, sezon)


# ------------------------------------------------------- model urzadzen (JSON)
def _min(t):
    h, m = map(int, t.split(":"))
    return h * 60 + m


def minuty_on(dev):
    s = dev.get("schedule")
    per_h = [0] * 24
    if s == "always_on":
        return [60] * 24
    if s == "always_off" or not isinstance(s, list):
        return per_h
    tl = {0: False}
    for ev in s:
        tl[_min(ev["at"])] = ev.get("action", "ON").upper() == "ON"
    klucze = sorted(tl)
    stan, i = False, 0
    for m in range(1440):
        while i < len(klucze) and klucze[i] <= m:
            stan = tl[klucze[i]]
            i += 1
        if stan:
            per_h[m // 60] += 1
    return per_h


def kwh_na_godzine(dev):
    t, p = dev["type"], dev.get("params", {})
    oh = minuty_on(dev)
    if t in ("BOILER", "REFRIGERATOR", "AC"):
        cyc = p.get("cycle_length_minutes", 43)
        duty = round(cyc * p.get("duty_cycle", 0.4)) / cyc
        return [p.get("power_w", 0) * (m / 60) * duty / 1000 for m in oh]
    if t == "COMPUTER":
        L, I = p.get("burst_length_minutes", 5), p.get("burst_interval_minutes", 20)
        d = L / (L + (I - 1) / 2)
        w = d * p.get("burst_power_w", 300) + (1 - d) * p.get("idle_power_w", 100)
        return [w * (m / 60) / 1000 for m in oh]
    if t in STANOWE:
        return [p.get("power_w", 0) * (m / 60) / 1000 for m in oh]

    out = [0.0] * 24
    sched = dev.get("schedule") if isinstance(dev.get("schedule"), list) else []
    for ev in sched:
        if ev.get("action", "ON").upper() != "ON":
            continue
        start = _min(ev["at"])
        if t == "DISHWASHER":
            fazy = [(p.get("heat_phase_minutes", 10), p.get("heat_power_w", 1800)),
                    (p.get("wash_phase_minutes", 60), p.get("wash_power_w", 200)),
                    (p.get("dry_phase_minutes", 20), p.get("dry_power_w", 1500))]
        elif t == "OVEN":
            on_m, off_m = p.get("heat_on_minutes", 5), p.get("heat_off_minutes", 3)
            fazy = [(p.get("cycle_minutes", 60), p.get("power_w", 2500) * on_m / (on_m + off_m))]
        else:
            fazy = [(p.get("cycle_minutes", 3 if t == "KETTLE" else 60),
                     p.get("power_w", 2000))]
        kursor = start
        for dur, w in fazy:
            for i in range(int(dur)):
                m = kursor + i
                if m >= 1440:
                    break
                out[m // 60] += w / 60 / 1000
            kursor += int(dur)
    return out


def dekompozycja(cfg):
    """Analityczny podzial na skladnik elastyczny (wektor) i cykle AGD/bojler."""
    elast = [0.0] * 24
    sztywne = [0.0] * 24
    cykle = []
    boj_kwh, boj_kw = 0.0, 0.0
    for dev in cfg["devices"]:
        wek = kwh_na_godzine(dev)
        t = dev["type"]
        if t == "BOILER":
            boj_kwh += sum(wek)
            boj_kw = max(boj_kw, dev.get("params", {}).get("power_w", 0) / 1000)
            for h in range(24):
                elast[h] += wek[h]
        elif t in ("WASHER", "DISHWASHER"):
            p = dev.get("params", {})
            if t == "DISHWASHER":
                dl = (p.get("heat_phase_minutes", 10) + p.get("wash_phase_minutes", 60)
                      + p.get("dry_phase_minutes", 20))
            else:
                dl = p.get("cycle_minutes", 60)
            sched = dev.get("schedule") if isinstance(dev.get("schedule"), list) else []
            n = sum(1 for e in sched if e.get("action", "ON").upper() == "ON")
            if n:
                for _ in range(n):
                    cykle.append([sum(wek) / n, max(1, math.ceil(dl / 60))])
            for h in range(24):
                elast[h] += wek[h]
        else:
            for h in range(24):
                sztywne[h] += wek[h]
    return sztywne, elast, cykle, boj_kwh, boj_kw


# ------------------------------------------------------------- sterowanie
def okna_startu(dlugosc):
    ok = []
    for s in range(24):
        koniec = s + dlugosc
        if s >= 21 or koniec <= DEADLINE_AGD:
            if (koniec if s < DEADLINE_AGD else koniec - 24) <= DEADLINE_AGD:
                ok.append(s)
    return ok or list(range(24))


def rozloz_agd(ceny, cykle):
    wek = [0.0] * 24
    for kwh, dl in cykle:
        best, best_koszt = None, None
        for s in okna_startu(dl):
            godz = [(s + i) % 24 for i in range(dl)]
            koszt = sum(ceny[g] for g in godz) / dl
            if best_koszt is None or koszt < best_koszt:
                best, best_koszt = godz, koszt
        for g in best:
            wek[g] += kwh / dl
    return wek


def rozloz_bojler(ceny, kwh_doba, moc_kw):
    wek = [0.0] * 24
    if kwh_doba <= 0:
        return wek
    moc = moc_kw if moc_kw > 0 else kwh_doba / 24
    zostalo = kwh_doba
    wybrane = []
    for a, b in OKNA_CWU:
        g = min(range(a, b), key=lambda x: ceny[x])
        wybrane.append(g)
    for g in wybrane:
        por = min(moc, zostalo)
        wek[g] += por
        zostalo -= por
    for g in sorted(range(24), key=lambda x: ceny[x]):
        if zostalo <= 1e-9:
            break
        if g in wybrane:
            continue
        por = min(moc, zostalo)
        wek[g] += por
        zostalo -= por
    if zostalo > 1e-9:
        for g in range(24):
            wek[g] += zostalo / 24
    return wek


# ------------------------------------------------------------- dane wejsciowe
def wczytaj_profil(lit, sez):
    """Zmierzony sredni profil dobowy E_h [24] z 720 godzin symulacji."""
    p = PROFILE / f"{lit}_{sez}.csv"
    sumy, licz = [0.0] * 24, [0] * 24
    with open(p, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            h = int(r["hour"][11:13])
            sumy[h] += float(r["kwh"])
            licz[h] += 1
    if min(licz) == 0:
        raise SystemExit(f"[BLAD] {p}: brak danych dla godziny {licz.index(0)}")
    if len(set(licz)) != 1:
        print(f"  [UWAGA] {lit}-{sez}: nierowna liczba dob na godzine {sorted(set(licz))}")
    return [sumy[h] / licz[h] for h in range(24)], licz[0]


def wczytaj_ceny():
    def liczba(x):
        return float(str(x).replace(" ", "").replace(" ", "").replace(",", "."))
    ceny = defaultdict(lambda: defaultdict(dict))
    with open(CENY, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            ceny[r["sezon"]][str(r["data"])[:10]][int(r["godz"])] = liczba(r["cena_pln_mwh"])
    for s in SEZONY:
        pelne = [d for d, h in ceny[s].items() if len(h) == 24]
        if len(pelne) != 30:
            raise SystemExit(f"[BLAD] {s}: {len(pelne)}/30 pelnych dob w {CENY}")
    return ceny


def udzial_nocny(wek, sezon):
    return T.udzial_nocny(wek, sezon)


# ------------------------------------------------------------------- main
def main():
    for p in (CENY, PROFILE, SEASONAL):
        if not p.exists():
            raise SystemExit(f"[BLAD] Brak {p}")
    ceny = wczytaj_ceny()

    print()
    print("=" * 118)
    print("DEMAND RESPONSE (wariant B) NA ZMIERZONYCH PROFILACH - partia 24 przypadkow")
    print("=" * 118)
    print()
    print("KONTROLA DEKOMPOZYCJI  (model analityczny vs pomiar; korekta = energia przeniesiona z elast. do sztywnej)")
    print(f"  {'Przypadek':18s} {'E zmierz.':>10s} {'E analit.':>10s} {'k':>6s} {'elast.':>7s} {'korekta':>9s}")

    dane = {}
    for lit in "ABCDEF":
        for sez in SEZONY:
            Eh, ndob = wczytaj_profil(lit, sez)
            cfg = json.load(open(SEASONAL / f"{STEM[lit]}_{sez.lower()}.json", encoding="utf-8"))
            _, elast_a, cykle, boj_kwh, boj_kw = dekompozycja(cfg)
            tot_a = sum(elast_a) + sum(_)
            tot_m = sum(Eh)
            k = tot_m / tot_a if tot_a else 1.0

            elast = [v * k for v in elast_a]
            cykle = [[c[0] * k, c[1]] for c in cykle]
            boj_kwh *= k

            sztywne = [Eh[h] - elast[h] for h in range(24)]
            korekta = sum(-v for v in sztywne if v < 0)
            if korekta > 0:
                # obetnij sztywne do zera; ubytek zdejmij proporcjonalnie z bojlera i AGD
                for h in range(24):
                    if sztywne[h] < 0:
                        elast[h] += sztywne[h]
                        sztywne[h] = 0.0
                el_tot = sum(elast)
                skala = el_tot / (el_tot + korekta) if el_tot + korekta else 1.0
                boj_kwh *= skala
                cykle = [[c[0] * skala, c[1]] for c in cykle]

            flag = "  <-- SPRAWDZ" if korekta / tot_m > 0.01 else ""
            print(f"  {lit + '-' + sez:18s} {tot_m:9.2f}  {tot_a:9.2f}  {k:5.3f} "
                  f"{sum(elast) / tot_m:6.1%} {korekta / tot_m:8.2%}{flag}")
            dane[(lit, sez)] = (Eh, sztywne, cykle, boj_kwh, boj_kw, ndob)

    print()
    naglowek = (f"{'Profil':20s} {'Sezon':7s} {'elast.':>7s} | "
                f"{'RDN/G11 b.':>10s} {'RDN/G11 a.':>10s} | "
                f"{'RDN/G12 b.':>10s} {'RDN/G12 a.':>10s} | "
                f"{'zysk DR':>9s} {'noc b.':>7s} {'noc a.':>7s} {'rez.':>5s}")
    print("WYNIKI  (b. = scenariusz bierny, a. = aktywne sterowanie; zysk DR = oszczednosc RDN akt. vs RDN bier.)")
    print("        rez. = liczba dob (z 30), w ktorych sterownik RDN zrezygnowal z przesuniecia jako nieoplacalnego")
    print(naglowek)
    print("-" * len(naglowek))

    zb = []
    for lit in "ABCDEF":
        for sez in SEZONY:
            Eh, sztywne, cykle, boj_kwh, boj_kw, ndob = dane[(lit, sez)]
            k = dict(g11=0.0, g12_b=0.0, g12_a=0.0, rdn_b=0.0, rdn_a=0.0)
            noc_a_sum, n_dni, rezygnacje = 0.0, 0, 0
            for data, godziny in sorted(ceny[sez].items()):
                cd = {h: rdn_detal(godziny[h]) for h in range(24)}
                kb11 = sum(Eh[h] * G11 for h in range(24))
                kb12 = sum(Eh[h] * g12_cena(h, sez) for h in range(24))
                kbrdn = sum(Eh[h] * cd[h] for h in range(24))
                k["g11"] += kb11
                k["g12_b"] += kb12
                k["rdn_b"] += kbrdn

                akt_rdn = list(sztywne)
                for h, v in enumerate(rozloz_agd(cd, cykle)):
                    akt_rdn[h] += v
                for h, v in enumerate(rozloz_bojler(cd, boj_kwh, boj_kw)):
                    akt_rdn[h] += v
                ka_rdn = sum(akt_rdn[h] * cd[h] for h in range(24))
                # sterownik zna ceny na dobe naprzod, wiec rezygnuje z przesuniecia,
                # jesli harmonogram wynikowy wypada drozej niz bazowy
                if ka_rdn > kbrdn:
                    rezygnacje += 1
                    akt_rdn, ka_rdn = list(Eh), kbrdn
                k["rdn_a"] += ka_rdn

                cg12 = {h: g12_cena(h, sez) for h in range(24)}
                akt_g12 = list(sztywne)
                for h, v in enumerate(rozloz_agd(cg12, cykle)):
                    akt_g12[h] += v
                for h, v in enumerate(rozloz_bojler(cg12, boj_kwh, boj_kw)):
                    akt_g12[h] += v
                ka_g12 = sum(akt_g12[h] * cg12[h] for h in range(24))
                if ka_g12 > kb12:
                    ka_g12 = kb12
                k["g12_a"] += ka_g12
                noc_a_sum += udzial_nocny(akt_rdn, sez)
                n_dni += 1

            b11 = (k["g11"] - k["rdn_b"]) / k["g11"] * 100
            a11 = (k["g11"] - k["rdn_a"]) / k["g11"] * 100
            b12 = (k["g12_b"] - k["rdn_b"]) / k["g12_b"] * 100
            a12 = (k["g12_a"] - k["rdn_a"]) / k["g12_a"] * 100
            zysk = (k["rdn_b"] - k["rdn_a"]) / k["rdn_b"] * 100
            elast_udz = (sum(c[0] for c in cykle) + boj_kwh) / sum(Eh)
            noc_b = udzial_nocny(Eh, sez) * 100
            noc_a = noc_a_sum / n_dni * 100
            print(f"{ETYKIETA[lit]:20s} {sez:7s} {elast_udz:6.1%} | "
                  f"{b11:9.1f}% {a11:9.1f}% | {b12:9.1f}% {a12:9.1f}% | "
                  f"{zysk:8.1f}% {noc_b:6.1f}% {noc_a:6.1f}% {rezygnacje:5d}")
            zb.append(dict(lit=lit, sez=sez, b11=b11, a11=a11, b12=b12, a12=a12,
                           zysk=zysk, noc_b=noc_b, noc_a=noc_a, k=k, elast=elast_udz,
                           rezygnacje=rezygnacje))

    n = len(zb)
    print("-" * len(naglowek))
    print(f"{'SREDNIA (n=24)':28s} {sum(z['elast'] for z in zb) / n:6.1%} | "
          f"{sum(z['b11'] for z in zb) / n:9.1f}% {sum(z['a11'] for z in zb) / n:9.1f}% | "
          f"{sum(z['b12'] for z in zb) / n:9.1f}% {sum(z['a12'] for z in zb) / n:9.1f}% | "
          f"{sum(z['zysk'] for z in zb) / n:8.1f}% "
          f"{sum(z['noc_b'] for z in zb) / n:6.1f}% {sum(z['noc_a'] for z in zb) / n:6.1f}% "
          f"{sum(z['rezygnacje'] for z in zb) / n:5.1f}")

    S = lambda key: sum(z["k"][key] for z in zb)
    g11, g12b, g12a, rb, ra = S("g11"), S("g12_b"), S("g12_a"), S("rdn_b"), S("rdn_a")
    print(f"{'ILORAZ SREDNICH':28s} {'':6s} | "
          f"{(g11 - rb) / g11 * 100:9.1f}% {(g11 - ra) / g11 * 100:9.1f}% | "
          f"{(g12b - rb) / g12b * 100:9.1f}% {(g12a - ra) / g12a * 100:9.1f}% | "
          f"{(rb - ra) / rb * 100:8.1f}%")

    print()
    print("UJECIE KWOTOWE (suma po 24 przypadkach, 30 dob kazdy)")
    print(f"  G11 bierny              {g11:10.2f} zl")
    print(f"  G12 bierny              {g12b:10.2f} zl   aktywny {g12a:10.2f} zl   zysk {g12b - g12a:8.2f} zl ({(g12b - g12a) / g12b * 100:.1f}%)")
    print(f"  RDN bierny              {rb:10.2f} zl   aktywny {ra:10.2f} zl   zysk {rb - ra:8.2f} zl ({(rb - ra) / rb * 100:.1f}%)")
    print()
    print(f"  Najtansza kombinacja biernie:  {'RDN' if rb < min(g11, g12b) else ('G12' if g12b < g11 else 'G11')}")
    print(f"  Najtansza kombinacja aktywnie: {'RDN' if ra < min(g11, g12a) else ('G12' if g12a < g11 else 'G11')}")
    print(f"  Przewaga RDN akt. nad G12 akt.: {(g12a - ra) / g12a * 100:.2f}%")

    print()
    print("ROZPIETOSC WEWNATRZDOBOWA CENY DETALICZNEJ (srednia po 30 dobach sezonu)")
    print(f"  {'Sezon':8s} {'RDN max-min':>12s} {'RDN 6 najt.':>12s} {'G12 max-min':>12s}")
    g12_rozp = T.G12_DZIEN - T.G12_NOC
    for s in SEZONY:
        r_amp, r_tan = [], []
        for _d, gz in ceny[s].items():
            cd = sorted(rdn_detal(gz[h]) for h in range(24))
            r_amp.append(cd[-1] - cd[0])
            r_tan.append(sum(cd[:6]) / 6)
        sr_amp = sum(r_amp) / len(r_amp)
        print(f"  {s:8s} {sr_amp:11.3f}  {sum(r_tan) / len(r_tan):11.3f}  {g12_rozp:11.3f}")
    print(f"  Uwaga: skladnik staly ceny RDN (siec + oplaty systemowe + marza = {T.NARZUT:.4f} zl/kWh netto)")
    print("  nie podlega przesunieciu, co tlumi wewnatrzdobowa rozpietosc taryfy dynamicznej.")
    print()

    WYNIKI.mkdir(exist_ok=True)
    out = WYNIKI / "demand_response_zmierzone.csv"
    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["profil", "sezon", "udzial_elastyczny_proc",
                    "rdn_vs_g11_bierny_proc", "rdn_vs_g11_aktywny_proc",
                    "rdn_vs_g12_bierny_proc", "rdn_vs_g12_aktywny_proc",
                    "zysk_dr_proc", "udzial_nocny_bierny_proc", "udzial_nocny_aktywny_proc",
                    "koszt_g11_zl", "koszt_g12_bierny_zl", "koszt_g12_aktywny_zl",
                    "koszt_rdn_bierny_zl", "koszt_rdn_aktywny_zl"])
        for z in zb:
            fm = lambda v: f"{v:.4f}".replace(".", ",")
            w.writerow([ETYKIETA[z["lit"]], z["sez"], fm(z["elast"] * 100),
                        fm(z["b11"]), fm(z["a11"]), fm(z["b12"]), fm(z["a12"]),
                        fm(z["zysk"]), fm(z["noc_b"]), fm(z["noc_a"]),
                        fm(z["k"]["g11"]), fm(z["k"]["g12_b"]), fm(z["k"]["g12_a"]),
                        fm(z["k"]["rdn_b"]), fm(z["k"]["rdn_a"])])
    print(f"Zapisano: {out}")
    print()


if __name__ == "__main__":
    main()
