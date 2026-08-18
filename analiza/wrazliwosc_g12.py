"""
Analiza wrazliwosci wnioskow na parametry modelu kosztowego.

Stawki G11 i G12 sa ustalone dokumentami urzedowymi (patrz taryfy.py), wiec nie
sa juz przedmiotem sporu. Wolne pozostaja trzy rzeczy i ta analiza obejmuje
kazda z nich:

  1. MARZA SPRZEDAWCY w taryfie dynamicznej - jedyny parametr bez umocowania
     w taryfie zatwierdzonej przez URE. Decyduje o ZNAKU wyniku RDN vs G11.
  2. DEFINICJA STREF G12 - sezonowa (zgodna z taryfa OSD) wobec calorocznej
     (uzytej w pierwszej wersji analizy) oraz wariant z zegarem licznika
     nieprzestawianym na czas letni (pkt 3.2.2 taryfy PGE Obrot).
  3. STAWKI G12 - siatka hipotetycznych par, gdyby przyjac inna taryfe
     (np. innego OSD). Pokazuje, ze mechanizm wyniku jest jeden.

  Caly wynik porownania G11 z G12 sprowadza sie do progu udzialu strefy nocnej:

        tau = (c_dzien - c_G11) / (c_dzien - c_noc)

  G12 jest tansza od G11 dokladnie wtedy, gdy udzial zuzycia w strefie tanszej
  przekracza tau. Punkt 5 weryfikuje te rownowaznosc numerycznie.

URUCHOMIENIE
  python wrazliwosc_g12.py
"""
import csv
from collections import defaultdict
from pathlib import Path

import taryfy as T

ANALIZA = Path(__file__).resolve().parent
PROFILE = ANALIZA / "dane_zrodlowe" / "profile"
CENY = ANALIZA / "dane_zrodlowe" / "ceny" / "wszystkie.csv"
WYNIKI = ANALIZA / "wyniki_analizy"

ETYKIETA = {"A": "Singiel pracujacy", "B": "Pracownik zdalny", "C": "Rodzina 2+2",
            "D": "Senior samotny", "E": "Studenci", "F": "Para bez dzieci"}

MARZE = [0.00, 0.02, 0.05, 0.10, 0.15]
DZIEN_SIATKA = [1.15, 1.20, 1.2491, 1.30]
NOC_SIATKA = [0.55, 0.6111, 0.70, 0.80]


def wczytaj_profile():
    prof = {}
    for lit in "ABCDEF":
        for sez in T.SEZONY:
            p = PROFILE / f"{lit}_{sez}.csv"
            sumy, licz = [0.0] * 24, [0] * 24
            with open(p, encoding="utf-8-sig", newline="") as f:
                for r in csv.DictReader(f):
                    h = int(r["hour"][11:13])
                    sumy[h] += float(r["kwh"])
                    licz[h] += 1
            if min(licz) == 0:
                raise SystemExit(f"[BLAD] {p}: brak danych dla godziny {licz.index(0)}")
            prof[(lit, sez)] = [sumy[h] / licz[h] for h in range(24)]
    return prof


def wczytaj_ceny():
    ceny = defaultdict(lambda: defaultdict(dict))
    with open(CENY, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            ceny[r["sezon"]][str(r["data"])[:10]][int(r["godz"])] = float(
                str(r["cena_pln_mwh"]).replace(",", "."))
    for s in T.SEZONY:
        pelne = [d for d, h in ceny[s].items() if len(h) == 24]
        if len(pelne) != 30:
            raise SystemExit(f"[BLAD] {s}: {len(pelne)}/30 pelnych dob")
    return ceny


def przelicz(prof, ceny, dzien=None, noc=None, marza=None,
             sezonowe=True, przesuniecie=0):
    """(RDN vs G11 %, RDN vs G12 %, G12 vs G11 %, lista przypadkow G11<G12, szczegoly)."""
    stan = (T.STREFY_SEZONOWE, T.PRZESUNIECIE_LETNIE)
    T.STREFY_SEZONOWE, T.PRZESUNIECIE_LETNIE = sezonowe, przesuniecie
    nar = None if marza is None else T.SIEC_G11 + T.SYSTEMOWE + marza
    try:
        s11 = s12 = srdn = 0.0
        wygrane, szczegoly = [], []
        for lit in "ABCDEF":
            for sez in T.SEZONY:
                E = prof[(lit, sez)]
                k11 = k12 = krdn = 0.0
                for _d, gz in ceny[sez].items():
                    k11 += sum(E[h] * T.G11 for h in range(24))
                    k12 += sum(E[h] * T.g12_cena(h, sez, dzien, noc) for h in range(24))
                    krdn += sum(E[h] * T.rdn_detal(gz[h], nar) for h in range(24))
                if k11 < k12:
                    wygrane.append(f"{lit}-{sez}")
                szczegoly.append((lit, sez, T.udzial_nocny(E, sez), k11, k12, krdn))
                s11 += k11
                s12 += k12
                srdn += krdn
        return ((s11 - srdn) / s11 * 100, (s12 - srdn) / s12 * 100,
                (s11 - s12) / s11 * 100, wygrane, szczegoly)
    finally:
        T.STREFY_SEZONOWE, T.PRZESUNIECIE_LETNIE = stan


def main():
    prof = wczytaj_profile()
    ceny = wczytaj_ceny()

    print()
    print("=" * 100)
    print("WRAZLIWOSC WNIOSKOW NA PARAMETRY MODELU KOSZTOWEGO")
    print(f"Stawki bazowe: G11 {T.G11:.4f} | G12 {T.G12_DZIEN:.4f}/{T.G12_NOC:.4f} zl/kWh brutto")
    print("=" * 100)

    # ------------------------------------------------------------------ 1
    print()
    print("1. MARZA SPRZEDAWCY W TARYFIE DYNAMICZNEJ  (jedyny parametr swobodny)")
    print()
    print(f"   {'marza [zl/kWh]':>15s} {'narzut':>8s} {'RDN vs G11':>11s} {'RDN vs G12':>11s} {'prog hurtowy':>13s}")
    zapis_marze = []
    for m in MARZE:
        a, b, _c, _w, _s = przelicz(prof, ceny, marza=m)
        nar = T.SIEC_G11 + T.SYSTEMOWE + m
        prog = T.prog_ceny_hurtowej(narzut=nar)
        gwiazdka = "  <-- baza" if abs(m - T.MARZA) < 1e-9 else ""
        print(f"   {m:15.2f} {nar:8.4f} {a:10.2f}% {b:10.2f}% {prog:10.0f} zl/MWh{gwiazdka}")
        zapis_marze.append((m, nar, a, b, prog))

    lo, hi = 0.0, 1.0
    for _ in range(80):
        mid = (lo + hi) / 2
        if przelicz(prof, ceny, marza=mid)[0] > 0:
            lo = mid
        else:
            hi = mid
    print()
    print(f"   Marza progowa, przy ktorej RDN zrownuje sie z G11: {lo * 100:.2f} gr/kWh")
    a0, b0, _, _, _ = przelicz(prof, ceny, marza=0.0)
    print(f"   Przy marzy zerowej RDN vs G12 = {b0:.2f}% - taryfa dynamiczna nie dorownuje")
    print("   taryfie dwustrefowej nawet bez narzutu handlowego.")

    # ------------------------------------------------------------------ 2
    print()
    print("2. DEFINICJA STREF CZASOWYCH G12")
    print()
    print(f"   {'Wariant':46s} {'G12 vs G11':>11s} {'RDN vs G12':>11s} {'G11>G12':>8s}")
    warianty_stref = [
        ("sezonowe wg taryfy OSD (13-15 zima / 15-17 lato)", True, 0),
        ("caloroczne 13-15 (pierwsza wersja analizy)", False, 0),
        ("sezonowe, zegar licznika nieprzestawiany (+1 h latem)", True, 1),
    ]
    for opis, sezonowe, przes in warianty_stref:
        _a, b, c, w, _s = przelicz(prof, ceny, sezonowe=sezonowe, przesuniecie=przes)
        print(f"   {opis:46s} {c:10.2f}% {b:10.2f}% {len(w):5d}/24")

    # ------------------------------------------------------------------ 3
    print()
    print("3. ZMIERZONY UDZIAL STREFY TANSZEJ G12  [%]   (sezonowa / caloroczna)")
    print()
    print(f"   {'Profil':20s} " + " ".join(f"{s:>15s}" for s in T.SEZONY))
    for lit in "ABCDEF":
        kom = []
        for sez in T.SEZONY:
            E = prof[(lit, sez)]
            T.STREFY_SEZONOWE = True
            a = T.udzial_nocny(E, sez) * 100
            T.STREFY_SEZONOWE = False
            b = T.udzial_nocny(E, sez) * 100
            T.STREFY_SEZONOWE = True
            kom.append(f"{a:6.1f} /{b:6.1f}")
        print(f"   {ETYKIETA[lit]:20s} " + " ".join(f"{k:>15s}" for k in kom))
    print(f"   Prog tau = {T.prog_udzialu_nocnego() * 100:.2f}%")

    # ------------------------------------------------------------------ 4
    print()
    print("4. PROG tau DLA HIPOTETYCZNYCH STAWEK G12  [%]")
    print(f"   przy c_G11 = {T.G11:.4f} zl/kWh")
    print()
    print("   c_dzien \\ c_noc " + " ".join(f"{n:>8.2f}" for n in NOC_SIATKA))
    for d in DZIEN_SIATKA:
        kom = []
        for n in NOC_SIATKA:
            if d <= n:
                kom.append("     n/d")
            else:
                kom.append(f"{T.prog_udzialu_nocnego(dzien=d, noc=n) * 100:8.1f}")
        print(f"   {d:>13.4f}   " + " ".join(kom))

    # ------------------------------------------------------------------ 5
    print()
    print("5. WERYFIKACJA: prog analityczny wobec wyniku numerycznego")
    bledy = sprawdzone = 0
    for d in DZIEN_SIATKA:
        for n in NOC_SIATKA:
            if d <= n:
                continue
            for sezonowe in (True, False):
                _a, _b, _c, _w, szcz = przelicz(prof, ceny, dzien=d, noc=n, sezonowe=sezonowe)
                tau = T.prog_udzialu_nocnego(dzien=d, noc=n)
                stan = T.STREFY_SEZONOWE
                T.STREFY_SEZONOWE = sezonowe
                for lit, sez, udzial, k11, k12, _kr in szcz:
                    sprawdzone += 1
                    if (udzial < tau) != (k11 < k12):
                        bledy += 1
                        print(f"   [ROZBIEZNOSC] {lit}-{sez} {d}/{n}: udzial {udzial:.4f}, tau {tau:.4f}")
                T.STREFY_SEZONOWE = stan
    print(f"   Sprawdzono {sprawdzone} kombinacji, rozbieznosci: {bledy}"
          + ("  [OK]" if bledy == 0 else "  [BLAD]"))

    # ------------------------------------------------------------------ zapis
    WYNIKI.mkdir(exist_ok=True)
    out = WYNIKI / "wrazliwosc_g12.csv"
    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        fm = lambda v: f"{v:.4f}".replace(".", ",")
        w.writerow(["marza_zl_kwh", "narzut_zl_kwh", "rdn_vs_g11_proc",
                    "rdn_vs_g12_proc", "prog_ceny_hurtowej_zl_mwh"])
        for m, nar, a, b, prog in zapis_marze:
            w.writerow([fm(m), fm(nar), fm(a), fm(b), fm(prog)])
    print()
    print(f"Zapisano: {out}")
    print()


if __name__ == "__main__":
    main()
