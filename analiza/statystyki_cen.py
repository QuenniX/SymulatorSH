"""
Statystyki opisowe cen Rynku Dnia Nastepnego dla czterech sezonow 2025.

Uzupelnia luke dowodowa rozdzialu Wyniki: teza o oplacalnosci taryfy dynamicznej
wiosna opiera sie na twierdzeniu o ujemnych cenach w godzinach szczytu
fotowoltaicznego, ktore nigdzie w pracy nie jest poparte liczbami.

Z tozsamosci K_RDN = 30 * suma_h(E_h * c_h) wynika, ze miesieczny koszt taryfy
dynamicznej zalezy WYLACZNIE od usrednionego dobowego ksztaltu cen. Wykres tych
czterech krzywych jest wiec bezposrednim wyjasnieniem calego wyniku sezonowego.

Progi odniesienia (parametry taryfowe 2026):
  459 zl/MWh - powyzej RDN jest drozsza od G11 (przy srednim wazeniu zuzyciem)
  581 zl/MWh - powyzej RDN jest drozsza od G12 w strefie dziennej
   69 zl/MWh - powyzej RDN jest drozsza od G12 w strefie nocnej

WEJSCIE   analiza/ceny_sezony_2025.csv  (sezon, data, godz, cena_pln_mwh)
WYJSCIE   konsola + praca_magisterska/figures/wykres_8_ceny_dobowe.png
"""
import csv
import statistics as st
from collections import defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

TU = Path(__file__).resolve().parent
CSV = TU / "ceny_sezony_2025.csv"
FIG = TU.parent / "praca_magisterska" / "figures" / "wykres_8_ceny_dobowe.png"

SEZONY = ["Zima", "Wiosna", "Lato", "Jesien"]
MIESIACE = {"Zima": "styczen", "Wiosna": "kwiecien", "Lato": "lipiec", "Jesien": "pazdziernik"}

# Paleta zwalidowana pod katem rozroznialnosci przy wadach widzenia barw
# (wszystkie kontrole przechodza; poprzednia para Lato/Jesien miala deltaE 7,8
# przy normalnym widzeniu, czyli ponizej progu rozroznialnosci).
BARWY = {"Zima": "#1F5FA9", "Wiosna": "#5BB98C", "Lato": "#E8A33D", "Jesien": "#A8443A"}
STYLE = {"Zima": "-", "Wiosna": "--", "Lato": "-.", "Jesien": ":"}
MARKER = {"Zima": "o", "Wiosna": "s", "Lato": "^", "Jesien": "D"}

PROG_G11 = 459.3
PROG_G12_DZIEN = 581.3
PROG_G12_NOC = 69.1
G12_NOC_H = {22, 23, 0, 1, 2, 3, 4, 5, 13, 14}


def liczba(x):
    return float(str(x).replace(" ", "").replace(" ", "").replace(",", "."))


def main():
    if not CSV.exists():
        print(f"[BLAD] brak {CSV}")
        return

    dane = defaultdict(lambda: defaultdict(dict))   # sezon -> data -> godz -> cena
    with open(CSV, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            dane[r["sezon"]][str(r["data"])[:10]][int(r["godz"])] = liczba(r["cena_pln_mwh"])

    print()
    print("TABELA. Statystyki opisowe cen RDN wedlug sezonow (2025, zl/MWh)")
    print()
    hdr = (f"{'Sezon':8s} {'miesiac':11s} {'dob':>4s} {'srednia':>8s} {'mediana':>8s} "
           f"{'odch.':>7s} {'min':>8s} {'max':>8s} {'h<0':>5s} {'h<69':>6s} {'h>459':>7s} {'h>581':>7s}")
    print(hdr)
    print("-" * len(hdr))

    profile = {}
    for s in SEZONY:
        dni = dane.get(s, {})
        ceny = [c for h in dni.values() for c in h.values()]
        if not ceny:
            continue
        prof = [st.mean([h[g] for h in dni.values() if g in h]) for g in range(24)]
        profile[s] = prof
        print(f"{s:8s} {MIESIACE[s]:11s} {len(dni):4d} {st.mean(ceny):8.1f} {st.median(ceny):8.1f} "
              f"{st.pstdev(ceny):7.1f} {min(ceny):8.1f} {max(ceny):8.1f} "
              f"{sum(1 for c in ceny if c < 0):5d} {sum(1 for c in ceny if c < PROG_G12_NOC):6d} "
              f"{sum(1 for c in ceny if c > PROG_G11):7d} {sum(1 for c in ceny if c > PROG_G12_DZIEN):7d}")

    print()
    print("PROFIL DOBOWY c_h (srednia cena hurtowa w danej godzinie doby, zl/MWh)")
    print()
    print("godz " + "".join(f"{s:>10s}" for s in SEZONY) + "   strefa G12")
    for g in range(24):
        strefa = "noc" if g in G12_NOC_H else "dzien"
        print(f"{g:4d} " + "".join(f"{profile[s][g]:10.0f}" if s in profile else f"{'-':>10s}"
                                   for s in SEZONY) + f"   {strefa}")

    print()
    print("INTERPRETACJA")
    for s in SEZONY:
        if s not in profile:
            continue
        prof = profile[s]
        plaska = st.mean(prof)
        dolek_h = min(range(24), key=lambda g: prof[g])
        szczyt_h = max(range(24), key=lambda g: prof[g])
        ponizej = [g for g in range(24) if prof[g] < PROG_G12_NOC]
        print(f"  {s:7s}: srednia dobowa {plaska:5.0f} zl/MWh "
              f"({'ponizej' if plaska < PROG_G11 else 'powyzej'} progu G11 = {PROG_G11:.0f}), "
              f"dolek {prof[dolek_h]:4.0f} o {dolek_h:02d}:00, szczyt {prof[szczyt_h]:4.0f} o {szczyt_h:02d}:00")
        print(f"           godziny ze srednia ponizej progu G12-noc: "
              f"{ponizej if ponizej else 'brak'}")

    # ---------------------------------------------------------------- wykres
    fig, ax = plt.subplots(figsize=(10, 5.6))
    ax.set_axisbelow(True)
    ax.grid(True, alpha=0.25, linewidth=0.7)

    for g in range(24):
        if g in G12_NOC_H:
            ax.axvspan(g - 0.5, g + 0.5, color="#000000", alpha=0.04, linewidth=0)

    # Etykiety progow z biala podkladka i w wolnych obszarach wykresu - inaczej
    # prog 459 nachodzi na krzywa zimowa w okolicach godziny 21.
    for prog, etykieta, x, ha in [
        (PROG_G11, f"prog RDN vs G11 ({PROG_G11:.0f})", 1.0, "left"),
        (PROG_G12_NOC, f"prog RDN vs G12-noc ({PROG_G12_NOC:.0f})", 23.2, "right"),
    ]:
        ax.axhline(prog, color="#555555", linewidth=1.0, linestyle=(0, (6, 4)), alpha=0.8)
        ax.text(x, prog + 12, etykieta, fontsize=8, color="#444444", ha=ha, va="bottom",
                bbox=dict(facecolor="white", alpha=0.85, edgecolor="none", pad=1.5))

    for s in SEZONY:
        if s not in profile:
            continue
        ax.plot(range(24), profile[s], color=BARWY[s], linestyle=STYLE[s], marker=MARKER[s],
                markersize=4.5, linewidth=2.0, label=f"{s} ({MIESIACE[s]})",
                markeredgecolor="white", markeredgewidth=0.6)
        gmax = max(range(24), key=lambda g: profile[s][g])
        ax.annotate(s, xy=(gmax, profile[s][gmax]), xytext=(3, 5), textcoords="offset points",
                    fontsize=9, color=BARWY[s], fontweight="bold")

    ax.set_xlabel("Godzina doby")
    ax.set_ylabel("Srednia cena hurtowa RDN [zl/MWh]")
    ax.set_title("Wykres 8: Usredniony dobowy profil cen RDN wedlug sezonow (2025)\n"
                 "szare pasy - strefa nocna taryfy G12", fontsize=12)
    ax.set_xticks(range(0, 24, 2))
    ax.set_xlim(-0.5, 23.5)
    ax.legend(loc="upper left", frameon=True, fontsize=9)
    for sp in ("top", "right"):
        ax.spines[sp].set_visible(False)

    FIG.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(FIG, dpi=150, bbox_inches="tight")
    print()
    print(f"Wykres zapisany: {FIG}")
    print()


if __name__ == "__main__":
    main()
