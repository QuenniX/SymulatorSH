"""
================================================================================
  analiza_wyniki.py - Analiza opłacalności taryf G11/G12/RDN
  (metodologia niepodważalna - Opcja C: re-kalkulacja z historycznymi cenami)
================================================================================

Skrypt do rozdziału Wyniki pracy magisterskiej. Bierze 24 testy baseline
(6 profili × 4 sezony harmonogramowe) uruchomione na EC2 i re-kalkuluje
koszty z historycznymi cenami RDN z 4 różnych sezonów meteorologicznych 2025.

Metodologia:
  1. Z każdego testu wyciągamy PROFIL DOBOWY zużycia (24 wartości godzinowe,
     uśrednione po 30 dniach symulacji).
  2. Dla każdego sezonu meteorologicznego bierzemy pełne 30 dni historycznych
     cen RDN 2025 (30 dni × 24 godziny = 720 punktów).
  3. Koszt sezonowy = suma po 30 dniach × 24h [ profil[h] × cena[d,h] ]
  4. VaR/CVaR liczone na 30 dziennych sumach kosztu RDN.

Sezony meteorologiczne (standard WMO):
  Zima   = styczeń  2025 (30 dni, środek zimy meteo dec-jan-feb)
  Wiosna = kwiecień 2025 (30 dni, środek wiosny mar-apr-may)
  Lato   = lipiec   2025 (30 dni, środek lata jun-jul-aug)
  Jesień = październik 2025 (30 dni, środek jesieni sep-oct-nov)

Wynik:
  - wyniki_analizy/tabela_zbiorcza.csv        pełna matryca 24×4 + agregacje
  - wyniki_analizy/tabela_zbiorcza.md         format markdown do wklejenia w pracy
  - wyniki_analizy/wykres_1_heatmap.png       heatmap diagonal 6×4
  - wyniki_analizy/wykres_2_ranking_arch.png  ranking profili
  - wyniki_analizy/wykres_3_boxplot.png       rozkład per sezon cenowy
  - wyniki_analizy/wykres_4_scatter_risk.png  savings vs CVaR (trade-off)
  - wyniki_analizy/wykres_5_bar_taryfy.png    porównanie 3 taryf side-by-side
  - wyniki_analizy/wykres_6_ranking_sezon.png ranking sezonów cenowych
  - wyniki_analizy/wykres_7_sensitivity.png   heatmap 24×4 (cross-season)
  - wyniki_analizy/wnioski_hipotezy.md        automatyczna weryfikacja H1-H5

Uruchomienie:
    pip install requests numpy pandas matplotlib
    python analiza_wyniki.py

Dla środowiska lokalnego zmień BASE_URL na http://localhost:8080/api/v1.
"""

import json
import os
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime
from typing import Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib as mpl
import requests

# ============================================================================
#  KONFIGURACJA
# ============================================================================

BASE_URL = "http://3.77.28.199/api/v1"
# BASE_URL = "http://localhost:8080/api/v1"

KEY_FILE = "api_key.txt"
BATCH_NAME_PREFIX = "[Partia 2026-08-06T18:57]"
OUTPUT_DIR = "wyniki_analizy"

# --- Sezony meteorologiczne 2025 (środek każdego sezonu, 30 dni) ---
SEASONS = {
    "Zima":   (date(2025, 1, 1),  date(2025, 1, 30)),
    "Wiosna": (date(2025, 4, 1),  date(2025, 4, 30)),
    "Lato":   (date(2025, 7, 1),  date(2025, 7, 30)),
    "Jesien": (date(2025, 10, 1), date(2025, 10, 30)),
}
SEASON_ORDER = ["Zima", "Wiosna", "Lato", "Jesien"]

# --- Taryfa 2026 (z backend/tariff/TariffParams.java - koniec tarczy mrozeniowej) ---
# Uzasadnienie: testy uruchomione w 2026, wiec backend rowniez uzyl parametrow 2026.
# Rok 2025 mial tarcze mrozeniowa (G11=0.75), ktora zaniza G11 sztucznie i sprawia
# ze RDN wyglada nieoplacalnie. 2026 to pierwszy rok bez tarczy - realny rynek.
G11_PLN_KWH = 1.10
G12_DAY_PLN_KWH = 1.25
G12_NIGHT_PLN_KWH = 0.62
G12_NIGHT_HOURS = {22, 23, 0, 1, 2, 3, 4, 5, 13, 14}

# --- RDN 2026: rdnFinal = (wholesale + 0.33 + 0.005 + 0.10) * 1.23 ---
RDN_DISTRIBUTION_NET = 0.33
RDN_EXCISE_NET = 0.005
RDN_MARGIN_NET = 0.10
VAT_MULTIPLIER = 1.23

# --- Profile i sezony harmonogramowe (z partii user'a) ---
PROFILES = {
    "A": "Singiel-biuro",
    "B": "Remote worker",
    "C": "Rodzina 2+2",
    "D": "Senior samotny",
    "E": "Studenci",
    "F": "Para DINK",
}
SCHEDULE_SEASONS = ["Zima", "Wiosna", "Lato", "Jesien"]

# --- Style matplotlib ---
mpl.rcParams["font.family"] = "DejaVu Sans"
mpl.rcParams["axes.grid"] = True
mpl.rcParams["grid.alpha"] = 0.3
mpl.rcParams["figure.dpi"] = 100
mpl.rcParams["savefig.dpi"] = 150
mpl.rcParams["savefig.bbox"] = "tight"


# ============================================================================
#  KLIENT API
# ============================================================================

def register_key() -> str:
    """POST /auth/register - jednorazowa rejestracja klucza API."""
    r = requests.post(f"{BASE_URL}/auth/register", json={
        "name": "Analiza Wyniki",
        "email": "analiza@magisterka.pl"
    }, timeout=15)
    r.raise_for_status()
    return r.json()["apiKey"]


def load_or_register_key() -> str:
    """Wczytuje klucz z pliku lub rejestruje nowy."""
    if os.path.exists(KEY_FILE):
        with open(KEY_FILE) as f:
            k = f.read().strip()
        print(f"  [OK] Wczytano klucz z {KEY_FILE}")
        return k
    print("  Rejestruje nowy klucz API...")
    k = register_key()
    with open(KEY_FILE, "w") as f:
        f.write(k)
    print(f"  [OK] Zapisano klucz do {KEY_FILE}")
    return k


def list_all_tests(api_key: str) -> List[dict]:
    """GET /tests - lista wszystkich testów."""
    r = requests.get(f"{BASE_URL}/tests",
                     headers={"X-API-Key": api_key},
                     timeout=30)
    r.raise_for_status()
    return r.json()


def get_test_costs(api_key: str, test_id: str) -> dict:
    """GET /tests/{id}/costs - koszty + hourlyBreakdown."""
    r = requests.get(f"{BASE_URL}/tests/{test_id}/costs",
                     headers={"X-API-Key": api_key},
                     timeout=60)
    r.raise_for_status()
    return r.json()


def get_prices_range(api_key: str, from_date: date, to_date: date) -> List[dict]:
    """GET /prices/range?from=xxx&to=xxx - historyczne ceny RDN."""
    r = requests.get(f"{BASE_URL}/prices/range",
                     params={"from": from_date.isoformat(), "to": to_date.isoformat()},
                     headers={"X-API-Key": api_key},
                     timeout=60)
    r.raise_for_status()
    return r.json()


# ============================================================================
#  PARSERY NAZW
# ============================================================================

# Nazwa testu: "[Partia 2026-08-03T19:25] Profil A: Singiel-biuro - Zima"
# UWAGA: "Singiel-biuro" ma mysllnik w srodku, wiec separator sezonu musi byc
# " - " (spacja-mysllnik-spacja), nie tylko "-". Etykieta labelu moze zawierac mysllniki.
NAME_PATTERN = re.compile(
    r"Profil\s+(?P<code>[A-F])[:\s]+(?P<label>.+?)\s+-\s+(?P<sezon>Zima|Wiosna|Lato|Jesien|Jesień)",
    re.IGNORECASE
)


def parse_test_name(name: str) -> Optional[Tuple[str, str]]:
    """Wyciąga (kod_profilu, sezon_harmonogramowy) z nazwy testu."""
    m = NAME_PATTERN.search(name)
    if not m:
        return None
    code = m.group("code").upper()
    sezon = m.group("sezon").capitalize().replace("ń", "n")
    return code, sezon


# ============================================================================
#  OBLICZENIA - profil, koszt, VaR/CVaR
# ============================================================================

def build_daily_profile(hourly_breakdown: List[dict]) -> np.ndarray:
    """
    Z listy 720 punktów hourly -> profil dobowy (24 wartości kWh).
    Uśredniamy zużycie w każdej godzinie doby po 30 dniach symulacji.
    """
    buckets = defaultdict(list)  # hour_of_day -> lista kWh
    for h in hourly_breakdown:
        # h['hour'] to LocalDateTime string, np. "2026-08-03T21:00:00"
        hour_of_day = datetime.fromisoformat(h["hour"]).hour
        buckets[hour_of_day].append(float(h["kwh"]))
    profile = np.zeros(24)
    for h in range(24):
        vals = buckets.get(h, [0.0])
        profile[h] = float(np.mean(vals))
    return profile


def rdn_final_price(wholesale_pln_kwh: float) -> float:
    """Formuła z TariffParams.java: (wholesale + narzuty) × VAT."""
    net = wholesale_pln_kwh + RDN_DISTRIBUTION_NET + RDN_EXCISE_NET + RDN_MARGIN_NET
    return net * VAT_MULTIPLIER


def g12_price_for_hour(hour: int) -> float:
    return G12_NIGHT_PLN_KWH if hour in G12_NIGHT_HOURS else G12_DAY_PLN_KWH


def group_prices_by_day(prices: List[dict]) -> Dict[str, Dict[int, float]]:
    """Grupuje listę cen do struktury {data: {godzina: cenaBrutto}}."""
    by_day: Dict[str, Dict[int, float]] = defaultdict(dict)
    for p in prices:
        d = p["deliveryDate"]
        h = int(p["hour"])
        wholesale = float(p["pricePlnKwh"])  # UWAGA: pricePlnKwh z API to hurtowa cena netto
        by_day[d][h] = rdn_final_price(wholesale)
    return by_day


@dataclass
class SeasonalCost:
    """Wynik re-kalkulacji dla jednego testu i jednego sezonu cenowego."""
    cost_g11: float
    cost_g12: float
    cost_rdn: float
    var_95_rdn: float
    cvar_95_rdn: float
    days_covered: int
    daily_costs_rdn: List[float]  # 30 wartości - do dalszej analizy


def recalculate_costs(
    daily_profile_kwh: np.ndarray,
    prices_by_day: Dict[str, Dict[int, float]],
) -> SeasonalCost:
    """
    Dla profilu dobowego (24 wartości kWh) i 30-dniowych cen sezonowych
    liczy pełen koszt każdej z 3 taryf + VaR/CVaR dla RDN.
    """
    daily_costs_rdn: List[float] = []
    total_g11 = 0.0
    total_g12 = 0.0
    total_rdn = 0.0

    for day, hour_prices in sorted(prices_by_day.items()):
        day_rdn = 0.0
        day_g11 = 0.0
        day_g12 = 0.0
        for h in range(24):
            kwh = float(daily_profile_kwh[h])
            price_rdn = hour_prices.get(h, 0.0)
            day_rdn += kwh * price_rdn
            day_g11 += kwh * G11_PLN_KWH
            day_g12 += kwh * g12_price_for_hour(h)
        daily_costs_rdn.append(day_rdn)
        total_rdn += day_rdn
        total_g11 += day_g11
        total_g12 += day_g12

    # VaR 95%: 95-ty percentyl dziennych kosztów RDN
    # CVaR 95%: średnia z ogona (kosztów >= VaR)
    if daily_costs_rdn:
        var_95 = float(np.percentile(daily_costs_rdn, 95))
        tail = [c for c in daily_costs_rdn if c >= var_95]
        cvar_95 = float(np.mean(tail)) if tail else var_95
    else:
        var_95 = 0.0
        cvar_95 = 0.0

    return SeasonalCost(
        cost_g11=total_g11,
        cost_g12=total_g12,
        cost_rdn=total_rdn,
        var_95_rdn=var_95,
        cvar_95_rdn=cvar_95,
        days_covered=len(daily_costs_rdn),
        daily_costs_rdn=daily_costs_rdn,
    )


# ============================================================================
#  MAIN FLOW - pobranie danych + rekalkulacja
# ============================================================================

def gather_all_data(api_key: str) -> Tuple[pd.DataFrame, Dict[str, np.ndarray]]:
    """
    Zwraca:
      - df: DataFrame z 96 wierszami (24 testy × 4 sezony cenowe)
      - profiles: dict profil_sezon -> profil dobowy (do wykresów)
    """
    print("\n[1/4] Pobieram liste testow z produkcji...")
    all_tests = list_all_tests(api_key)
    baseline = [t for t in all_tests if t.get("name", "").startswith(BATCH_NAME_PREFIX)]
    print(f"  Znaleziono {len(baseline)} testow z partii '{BATCH_NAME_PREFIX}'")
    if len(baseline) != 24:
        print(f"  [WARN] Oczekiwano 24 testow, znaleziono {len(baseline)}")

    print("\n[2/4] Pobieram profile godzinowe (24 testy)...")
    profiles: Dict[str, np.ndarray] = {}   # klucz: "A_Zima" -> profil dobowy
    test_meta: Dict[str, dict] = {}
    for i, t in enumerate(baseline, 1):
        parsed = parse_test_name(t["name"])
        if not parsed:
            print(f"  [WARN] Nie umiem sparsowac nazwy: {t['name']}")
            continue
        code, sezon = parsed
        print(f"    ({i}/{len(baseline)}) Profil {code} - {sezon}")
        costs = get_test_costs(api_key, t["testId"])
        hourly = costs.get("hourlyBreakdown", [])
        if not hourly:
            print(f"      [WARN] Brak hourlyBreakdown dla testu {t['testId']}")
            continue
        profile = build_daily_profile(hourly)
        key = f"{code}_{sezon}"
        profiles[key] = profile
        test_meta[key] = {"testId": t["testId"], "name": t["name"]}

    print(f"\n  [OK] Zbudowano {len(profiles)} profili dobowych")

    print("\n[3/4] Pobieram historyczne ceny RDN dla 4 sezonow 2025...")
    seasonal_prices: Dict[str, Dict[str, Dict[int, float]]] = {}
    for season_name, (from_d, to_d) in SEASONS.items():
        print(f"    {season_name}: {from_d} -> {to_d}")
        prices = get_prices_range(api_key, from_d, to_d)
        by_day = group_prices_by_day(prices)
        seasonal_prices[season_name] = by_day
        print(f"      Pobrano {len(prices)} rekordow ({len(by_day)} dni)")

    print("\n[4/4] Re-kalkulacja: 24 testy x 4 sezony cenowe = 96 wynikow...")
    rows = []
    for key, profile in profiles.items():
        code, sezon_harm = key.split("_")
        arch_label = PROFILES.get(code, code)
        for sezon_cen in SEASON_ORDER:
            sc = recalculate_costs(profile, seasonal_prices[sezon_cen])
            saving_vs_g11 = ((sc.cost_g11 - sc.cost_rdn) / sc.cost_g11 * 100) if sc.cost_g11 > 0 else 0
            saving_vs_g12 = ((sc.cost_g12 - sc.cost_rdn) / sc.cost_g12 * 100) if sc.cost_g12 > 0 else 0
            rows.append({
                "profil_kod": code,
                "profil_nazwa": arch_label,
                "sezon_harmonogram": sezon_harm,
                "sezon_ceny": sezon_cen,
                "koszt_G11_pln": round(sc.cost_g11, 2),
                "koszt_G12_pln": round(sc.cost_g12, 2),
                "koszt_RDN_pln": round(sc.cost_rdn, 2),
                "oszczednosc_RDN_vs_G11_pct": round(saving_vs_g11, 2),
                "oszczednosc_RDN_vs_G12_pct": round(saving_vs_g12, 2),
                "VaR95_RDN_pln": round(sc.var_95_rdn, 2),
                "CVaR95_RDN_pln": round(sc.cvar_95_rdn, 2),
                "dni_pokryte": sc.days_covered,
                "diagonal": sezon_harm == sezon_cen,
            })

    df = pd.DataFrame(rows)
    print(f"  [OK] Wygenerowano {len(df)} wierszy")
    return df, profiles


# ============================================================================
#  WYKRESY MATPLOTLIB (6 głównych + 1 sensitivity)
# ============================================================================

def wykres_1_heatmap_diagonal(df: pd.DataFrame, out_path: str) -> None:
    """Heatmap 6×4: profil × sezon (tryb diagonal - harmonogram=ceny)."""
    diag = df[df["diagonal"]].copy()
    pivot = diag.pivot_table(index="profil_kod", columns="sezon_harmonogram",
                              values="oszczednosc_RDN_vs_G11_pct", aggfunc="mean")
    pivot = pivot.reindex(index=list(PROFILES.keys()), columns=SEASON_ORDER)

    # Skala symetryczna wokol zera - pozytywne zielone, negatywne czerwone
    vmax = max(abs(np.nanmin(pivot.values)), abs(np.nanmax(pivot.values)))
    vmin = -vmax

    fig, ax = plt.subplots(figsize=(10, 6.5))
    im = ax.imshow(pivot.values, cmap="RdYlGn", aspect="auto", vmin=vmin, vmax=vmax)
    ax.set_xticks(range(len(SEASON_ORDER)))
    ax.set_xticklabels(SEASON_ORDER, fontsize=11)
    ax.set_yticks(range(len(PROFILES)))
    ax.set_yticklabels([f"{k}: {PROFILES[k]}" for k in PROFILES.keys()], fontsize=10)
    ax.set_title("Wykres 1: Oszczednosc RDN vs G11 [%] - tryb diagonalny\n"
                 "(harmonogram sezonowy urzadzen + ceny RDN z tego samego sezonu 2025)",
                 fontsize=12)

    for i in range(pivot.shape[0]):
        for j in range(pivot.shape[1]):
            v = pivot.values[i, j]
            if not np.isnan(v):
                # Czarny tekst dla ciepłych kolorow (zielony/zolty), bialy dla ciemnoczerwonych
                color = "white" if v < -vmax * 0.55 else "black"
                ax.text(j, i, f"{v:+.1f}%", ha="center", va="center",
                        color=color, fontweight="bold", fontsize=11)
    cbar = fig.colorbar(im, ax=ax, label="Oszczednosc [%]  (dodatnie=RDN taniej, ujemne=drozej)")
    cbar.ax.tick_params(labelsize=9)
    plt.tight_layout()
    plt.savefig(out_path)
    plt.close()


def wykres_2_ranking_profilow(df: pd.DataFrame, out_path: str) -> None:
    """Ranking profili - średnia oszczędność z 4 sezonów (diagonal)."""
    diag = df[df["diagonal"]].copy()
    ranking = diag.groupby("profil_kod")["oszczednosc_RDN_vs_G11_pct"].mean().sort_values(ascending=False)

    fig, ax = plt.subplots(figsize=(11, 6))
    labels = [f"{k}: {PROFILES[k]}" for k in ranking.index]
    colors = ["#d73027" if v < 0 else "#1a9850" for v in ranking.values]
    bars = ax.barh(labels, ranking.values, color=colors, edgecolor="black")
    ax.axvline(0, color="black", linewidth=1.0)
    ax.set_xlabel("Srednia oszczednosc RDN vs G11 [%] (usredniona po 4 sezonach)", fontsize=11)
    ax.set_title("Wykres 2: Ranking profilow gospodarstw domowych\n(im wyzej wartosc, tym bardziej oplacalny RDN)", fontsize=12)
    # Etykiety na koncu slupka (poza slupkiem zeby nie nakladalo sie na tekst osi Y)
    xmax = max(abs(ranking.min()), abs(ranking.max())) * 1.15
    ax.set_xlim(-xmax, xmax)
    for bar, v in zip(bars, ranking.values):
        offset = xmax * 0.02
        ax.text(v + (offset if v >= 0 else -offset), bar.get_y() + bar.get_height() / 2,
                f"{v:+.1f}%", va="center",
                ha="left" if v >= 0 else "right",
                fontweight="bold", fontsize=10)
    plt.tight_layout()
    plt.savefig(out_path)
    plt.close()


def wykres_3_boxplot_sezony(df: pd.DataFrame, out_path: str) -> None:
    """Box plot: rozkład oszczędności per sezon cenowy (wszystkie 96 punktów)."""
    fig, ax = plt.subplots(figsize=(9, 5.5))
    data = [df[df["sezon_ceny"] == s]["oszczednosc_RDN_vs_G11_pct"].values for s in SEASON_ORDER]
    bp = ax.boxplot(data, labels=SEASON_ORDER, patch_artist=True, showmeans=True,
                     meanprops={"marker": "D", "markerfacecolor": "red", "markersize": 8})
    palette = ["#3498db", "#2ecc71", "#f39c12", "#e67e22"]
    for patch, color in zip(bp["boxes"], palette):
        patch.set_facecolor(color)
        patch.set_alpha(0.6)
    ax.axhline(0, color="black", linewidth=0.8, linestyle="--")
    ax.set_ylabel("Oszczednosc RDN vs G11 [%]")
    ax.set_xlabel("Sezon cenowy")
    ax.set_title("Wykres 3: Rozklad oszczednosci per sezon cenowy\n"
                 "(6 profilow x 4 harmonogramy = 24 punkty per sezon; czerwony romb = srednia)")
    plt.tight_layout()
    plt.savefig(out_path)
    plt.close()


def wykres_4_scatter_savings_vs_risk(df: pd.DataFrame, out_path: str) -> None:
    """Scatter: oszczędność vs CVaR (klasyczny trade-off zysk/ryzyko)."""
    diag = df[df["diagonal"]].copy()
    fig, ax = plt.subplots(figsize=(9, 6.5))
    palette = {"Zima": "#3498db", "Wiosna": "#2ecc71", "Lato": "#f39c12", "Jesien": "#e67e22"}
    for sezon in SEASON_ORDER:
        sub = diag[diag["sezon_ceny"] == sezon]
        ax.scatter(sub["CVaR95_RDN_pln"], sub["oszczednosc_RDN_vs_G11_pct"],
                   s=140, color=palette[sezon], edgecolor="black", alpha=0.85, label=sezon)
        for _, row in sub.iterrows():
            ax.annotate(row["profil_kod"], (row["CVaR95_RDN_pln"], row["oszczednosc_RDN_vs_G11_pct"]),
                        xytext=(6, 6), textcoords="offset points", fontsize=9)
    ax.axhline(0, color="black", linewidth=0.8, linestyle="--")
    ax.set_xlabel("CVaR 95% dziennego kosztu RDN [zl] (im wiecej, tym wyzsze ryzyko)")
    ax.set_ylabel("Oszczednosc RDN vs G11 [%]")
    ax.set_title("Wykres 4: Trade-off zysk vs ryzyko dla 24 przypadkow diagonalnych\n"
                 "(prawy gorny rog = wysoka oszczednosc + wysokie ryzyko dnia drogi)")
    ax.legend(title="Sezon", loc="best")
    plt.tight_layout()
    plt.savefig(out_path)
    plt.close()


def wykres_5_bar_taryfy(df: pd.DataFrame, out_path: str) -> None:
    """Grupowany bar chart: średni koszt 3 taryf per profil (diagonal)."""
    diag = df[df["diagonal"]].copy()
    avg = diag.groupby("profil_kod")[["koszt_G11_pln", "koszt_G12_pln", "koszt_RDN_pln"]].mean()
    avg = avg.reindex(list(PROFILES.keys()))
    # Wywal wiersze z samymi NaN (np. profil bez danych)
    avg = avg.dropna(how="all")

    x = np.arange(len(avg))
    width = 0.26
    fig, ax = plt.subplots(figsize=(12, 6.5))
    ax.bar(x - width, avg["koszt_G11_pln"], width, label="G11 (plaska)", color="#7f8c8d", edgecolor="black")
    ax.bar(x, avg["koszt_G12_pln"], width, label="G12 (dzien/noc)", color="#3498db", edgecolor="black")
    ax.bar(x + width, avg["koszt_RDN_pln"], width, label="RDN (dynamiczna)", color="#e74c3c", edgecolor="black")
    ax.set_xticks(x)
    ax.set_xticklabels([f"{k}\n{PROFILES[k]}" for k in avg.index], fontsize=10)
    ax.set_ylabel("Sredni miesieczny koszt [zl]", fontsize=11)
    ax.set_title("Wykres 5: Porownanie kosztow 3 taryf per profil\n"
                 "(srednia z 4 sezonow diagonalnych, ceny 2026)", fontsize=12)
    ax.legend(fontsize=10, loc="best")
    plt.tight_layout()
    plt.savefig(out_path)
    plt.close()


def wykres_6_ranking_sezonow(df: pd.DataFrame, out_path: str) -> None:
    """Ranking sezonów cenowych - który sezon najbardziej korzysta na RDN."""
    diag = df[df["diagonal"]].copy()
    ranking = diag.groupby("sezon_ceny")["oszczednosc_RDN_vs_G11_pct"].mean().reindex(SEASON_ORDER)

    fig, ax = plt.subplots(figsize=(9, 6))
    colors = ["#3498db", "#2ecc71", "#f39c12", "#e67e22"]
    bars = ax.bar(ranking.index, ranking.values, color=colors, edgecolor="black")
    ax.axhline(0, color="black", linewidth=1.0)
    ax.set_ylabel("Srednia oszczednosc RDN vs G11 [%]", fontsize=11)
    ax.set_xlabel("Sezon cenowy", fontsize=11)
    ax.set_title("Wykres 6: Ranking sezonow cenowych\n(usredniona oszczednosc z 6 profilow diagonalnych)", fontsize=12)
    # Etykiety NAD lub POD slupkiem, nie w srodku - lepsza czytelnosc
    y_range = ranking.max() - ranking.min()
    padding = max(abs(y_range) * 0.05, 0.5)
    for bar, v in zip(bars, ranking.values):
        y_pos = v + padding if v >= 0 else v - padding
        va = "bottom" if v >= 0 else "top"
        ax.text(bar.get_x() + bar.get_width() / 2, y_pos,
                f"{v:+.1f}%", ha="center", va=va, fontweight="bold", fontsize=11)
    # Dodaj wiecej miejsca na etykiety
    ymin = min(ranking.min() * 1.2, -1) if ranking.min() < 0 else -1
    ymax = max(ranking.max() * 1.2, 1) if ranking.max() > 0 else 1
    ax.set_ylim(ymin, ymax)
    plt.tight_layout()
    plt.savefig(out_path)
    plt.close()


def wykres_7_sensitivity(df: pd.DataFrame, out_path: str) -> None:
    """Sensitivity heatmap 24×4: wszystkie kombinacje (harmonogram × ceny)."""
    df2 = df.copy()
    df2["etykieta"] = df2["profil_kod"] + "-" + df2["sezon_harmonogram"].str[:4]
    pivot = df2.pivot_table(index="etykieta", columns="sezon_ceny",
                             values="oszczednosc_RDN_vs_G11_pct", aggfunc="mean")
    pivot = pivot.reindex(columns=SEASON_ORDER)
    row_order = [f"{k}-{s[:4]}" for k in PROFILES.keys() for s in SCHEDULE_SEASONS]
    pivot = pivot.reindex(index=[r for r in row_order if r in pivot.index])

    vmax = max(abs(np.nanmin(pivot.values)), abs(np.nanmax(pivot.values)))
    vmin = -vmax

    fig, ax = plt.subplots(figsize=(10, 13))
    im = ax.imshow(pivot.values, cmap="RdYlGn", aspect="auto", vmin=vmin, vmax=vmax)
    ax.set_xticks(range(len(SEASON_ORDER)))
    ax.set_xticklabels([f"Ceny {s}" for s in SEASON_ORDER], fontsize=10)
    ax.set_yticks(range(len(pivot.index)))
    ax.set_yticklabels(pivot.index, fontsize=9)
    ax.set_title("Wykres 7 (sensitivity): Oszczednosc RDN vs G11 [%] - 96 kombinacji\n"
                 "(wiersz = profil+harmonogram, kolumna = sezon cenowy)", fontsize=12)
    for i in range(pivot.shape[0]):
        for j in range(pivot.shape[1]):
            v = pivot.values[i, j]
            if not np.isnan(v):
                color = "white" if v < -vmax * 0.55 else "black"
                ax.text(j, i, f"{v:+.0f}", ha="center", va="center",
                        color=color, fontsize=9, fontweight="bold")
    cbar = fig.colorbar(im, ax=ax, label="Oszczednosc [%]  (dodatnie=RDN taniej)")
    cbar.ax.tick_params(labelsize=9)
    plt.tight_layout()
    plt.savefig(out_path)
    plt.close()


# ============================================================================
#  TABELA ZBIORCZA + WNIOSKI
# ============================================================================

def zapisz_tabele(df: pd.DataFrame, out_dir: str) -> None:
    """Zapisuje tabelę w CSV i Markdown do wklejenia w pracy."""
    diag = df[df["diagonal"]].copy().sort_values(["profil_kod", "sezon_harmonogram"])
    cols = ["profil_kod", "profil_nazwa", "sezon_harmonogram",
            "koszt_G11_pln", "koszt_G12_pln", "koszt_RDN_pln",
            "oszczednosc_RDN_vs_G11_pct", "oszczednosc_RDN_vs_G12_pct",
            "VaR95_RDN_pln", "CVaR95_RDN_pln"]

    # CSV pełny (96 + diagonal)
    df.to_csv(os.path.join(out_dir, "tabela_zbiorcza_cross_season.csv"),
              index=False, sep=";", decimal=",")
    diag[cols].to_csv(os.path.join(out_dir, "tabela_zbiorcza_diagonal.csv"),
                       index=False, sep=";", decimal=",")

    # Markdown - dla wklejenia do pracy magisterskiej
    with open(os.path.join(out_dir, "tabela_zbiorcza.md"), "w", encoding="utf-8") as f:
        f.write("# Tabela zbiorcza - analiza opłacalności RDN\n\n")
        f.write("## Tryb diagonalny (24 realistyczne przypadki)\n\n")
        f.write("Harmonogram sezonowy urządzeń + ceny RDN z tego samego sezonu 2025.\n\n")
        f.write(diag[cols].to_markdown(index=False, floatfmt=".2f"))
        f.write("\n\n## Agregaty\n\n### Średnia per profil (diagonal)\n\n")
        agg_arch = diag.groupby(["profil_kod", "profil_nazwa"])[
            ["koszt_G11_pln", "koszt_G12_pln", "koszt_RDN_pln",
             "oszczednosc_RDN_vs_G11_pct", "oszczednosc_RDN_vs_G12_pct"]
        ].mean().reset_index()
        f.write(agg_arch.to_markdown(index=False, floatfmt=".2f"))
        f.write("\n\n### Średnia per sezon cenowy (diagonal)\n\n")
        agg_sezon = diag.groupby("sezon_harmonogram")[
            ["koszt_G11_pln", "koszt_G12_pln", "koszt_RDN_pln",
             "oszczednosc_RDN_vs_G11_pct", "oszczednosc_RDN_vs_G12_pct"]
        ].mean().reset_index()
        f.write(agg_sezon.to_markdown(index=False, floatfmt=".2f"))
        # Grand total
        f.write("\n\n### Podsumowanie ogólne (diagonal)\n\n")
        f.write(f"- **Średni koszt G11:** {diag['koszt_G11_pln'].mean():.2f} zł\n")
        f.write(f"- **Średni koszt G12:** {diag['koszt_G12_pln'].mean():.2f} zł\n")
        f.write(f"- **Średni koszt RDN:** {diag['koszt_RDN_pln'].mean():.2f} zł\n")
        f.write(f"- **Średnia oszczędność RDN vs G11:** {diag['oszczednosc_RDN_vs_G11_pct'].mean():.2f}%\n")
        f.write(f"- **Średnia oszczędność RDN vs G12:** {diag['oszczednosc_RDN_vs_G12_pct'].mean():.2f}%\n")


def zapisz_wnioski(df: pd.DataFrame, out_dir: str) -> None:
    """Automatyczna weryfikacja hipotez H1-H5."""
    diag = df[df["diagonal"]].copy()

    with open(os.path.join(out_dir, "wnioski_hipotezy.md"), "w", encoding="utf-8") as f:
        f.write("# Automatyczna weryfikacja hipotez badawczych\n\n")
        f.write("_Wygenerowane przez analiza_wyniki.py na podstawie 24 testów baseline._\n\n")

        # H1: RDN opłacalny średnio
        avg = diag["oszczednosc_RDN_vs_G11_pct"].mean()
        f.write(f"## H1: RDN jest średnio opłacalny wobec G11 dla polskich gospodarstw\n\n")
        f.write(f"**Wynik:** Średnia oszczędność = **{avg:.2f}%** (uśredniona po 24 przypadkach).\n")
        f.write(f"**Weryfikacja:** {'POTWIERDZONA' if avg > 0 else 'NIEPOTWIERDZONA'} (próg: >0%).\n\n")

        # H2: RDN opłacalny > G12
        avg_g12 = diag["oszczednosc_RDN_vs_G12_pct"].mean()
        f.write(f"## H2: RDN jest opłacalny również wobec G12 (dzień/noc)\n\n")
        f.write(f"**Wynik:** Średnia oszczędność = **{avg_g12:.2f}%**.\n")
        f.write(f"**Weryfikacja:** {'POTWIERDZONA' if avg_g12 > 0 else 'NIEPOTWIERDZONA'}.\n\n")

        # H3: Opłacalność różni się między profilami
        by_arch = diag.groupby("profil_kod")["oszczednosc_RDN_vs_G11_pct"].mean()
        best_arch = by_arch.idxmax()
        worst_arch = by_arch.idxmin()
        spread = by_arch.max() - by_arch.min()
        f.write(f"## H3: Opłacalność RDN różni się istotnie między profilami\n\n")
        f.write(f"**Wynik:** Najlepszy profil = **{best_arch} ({PROFILES[best_arch]})** "
                f"({by_arch.max():.2f}%), najgorszy = **{worst_arch} ({PROFILES[worst_arch]})** "
                f"({by_arch.min():.2f}%). Rozstęp = **{spread:.2f} pp**.\n")
        f.write(f"**Weryfikacja:** {'POTWIERDZONA' if spread > 2 else 'NIEPOTWIERDZONA'} (próg: rozstęp > 2 pp).\n\n")

        # H4: Zima ma większą oszczędność niż lato (większe amplitudy cen)
        by_sezon = diag.groupby("sezon_harmonogram")["oszczednosc_RDN_vs_G11_pct"].mean()
        zima = by_sezon.get("Zima", 0.0)
        lato = by_sezon.get("Lato", 0.0)
        f.write(f"## H4: Zima ma większą oszczędność na RDN niż lato (większe amplitudy cen)\n\n")
        f.write(f"**Wynik:** Zima = **{zima:.2f}%**, Lato = **{lato:.2f}%** (różnica: {zima - lato:.2f} pp).\n")
        f.write(f"**Weryfikacja:** {'POTWIERDZONA' if zima > lato else 'NIEPOTWIERDZONA'}.\n\n")

        # H5: Wnioski są robust (cross-season sensitivity)
        cross_by_arch = df.groupby("profil_kod")["oszczednosc_RDN_vs_G11_pct"].mean()
        cross_best = cross_by_arch.idxmax()
        f.write(f"## H5: Ranking profili jest robust względem sezonu cenowego\n\n")
        f.write(f"**Wynik:** Diagonal - najlepszy = **{best_arch}**. Cross-season 96 punktów - najlepszy = **{cross_best}**.\n")
        f.write(f"**Weryfikacja:** {'POTWIERDZONA' if best_arch == cross_best else 'CZĘŚCIOWA (wynik zależy od okresu cen)'}\n\n")

        # Dodatkowe insighty
        f.write(f"## Dodatkowe obserwacje\n\n")
        f.write(f"- Największa oszczędność (%): **{diag['oszczednosc_RDN_vs_G11_pct'].max():.2f}%** "
                f"({diag.loc[diag['oszczednosc_RDN_vs_G11_pct'].idxmax(), 'profil_nazwa']} - "
                f"{diag.loc[diag['oszczednosc_RDN_vs_G11_pct'].idxmax(), 'sezon_harmonogram']})\n")
        f.write(f"- Najmniejsza (najgorsza): **{diag['oszczednosc_RDN_vs_G11_pct'].min():.2f}%** "
                f"({diag.loc[diag['oszczednosc_RDN_vs_G11_pct'].idxmin(), 'profil_nazwa']} - "
                f"{diag.loc[diag['oszczednosc_RDN_vs_G11_pct'].idxmin(), 'sezon_harmonogram']})\n")
        f.write(f"- Średni miesięczny koszt: G11={diag['koszt_G11_pln'].mean():.2f} zł, "
                f"G12={diag['koszt_G12_pln'].mean():.2f} zł, RDN={diag['koszt_RDN_pln'].mean():.2f} zł\n")
        f.write(f"- Średni CVaR RDN (dzień drogi): **{diag['CVaR95_RDN_pln'].mean():.2f} zł** "
                f"(dla porównania: średni dzienny koszt G11 = {diag['koszt_G11_pln'].mean() / 30:.2f} zł)\n")


# ============================================================================
#  MAIN
# ============================================================================

def main() -> None:
    print("=" * 74)
    print("  ANALIZA WYNIKOW - metodologia niepodwazalna (Opcja C)")
    print("  Re-kalkulacja z historycznymi cenami RDN 2025 dla 4 sezonow meteo")
    print("=" * 74)

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    print(f"\n  Endpoint: {BASE_URL}")
    print(f"  Katalog wynikowy: {OUTPUT_DIR}/")

    print("\n[0/4] Autentykacja...")
    api_key = load_or_register_key()

    # Zbierz wszystko przez API
    df, profiles = gather_all_data(api_key)

    if df.empty:
        print("\n[BLAD] Nie zebrano zadnych danych - sprawdz partie testow.")
        sys.exit(1)

    # Zapisz surowe dane (do dalszej weryfikacji)
    print("\n[Wykresy] Generacja 7 wykresow matplotlib...")
    wykres_1_heatmap_diagonal(df, os.path.join(OUTPUT_DIR, "wykres_1_heatmap.png"))
    print("  [OK] wykres_1_heatmap.png")
    wykres_2_ranking_profilow(df, os.path.join(OUTPUT_DIR, "wykres_2_ranking_arch.png"))
    print("  [OK] wykres_2_ranking_arch.png")
    wykres_3_boxplot_sezony(df, os.path.join(OUTPUT_DIR, "wykres_3_boxplot.png"))
    print("  [OK] wykres_3_boxplot.png")
    wykres_4_scatter_savings_vs_risk(df, os.path.join(OUTPUT_DIR, "wykres_4_scatter_risk.png"))
    print("  [OK] wykres_4_scatter_risk.png")
    wykres_5_bar_taryfy(df, os.path.join(OUTPUT_DIR, "wykres_5_bar_taryfy.png"))
    print("  [OK] wykres_5_bar_taryfy.png")
    wykres_6_ranking_sezonow(df, os.path.join(OUTPUT_DIR, "wykres_6_ranking_sezon.png"))
    print("  [OK] wykres_6_ranking_sezon.png")
    wykres_7_sensitivity(df, os.path.join(OUTPUT_DIR, "wykres_7_sensitivity.png"))
    print("  [OK] wykres_7_sensitivity.png")

    print("\n[Tabela] Zapisuje tabele CSV i Markdown...")
    zapisz_tabele(df, OUTPUT_DIR)
    print(f"  [OK] tabela_zbiorcza_diagonal.csv, tabela_zbiorcza_cross_season.csv, tabela_zbiorcza.md")

    print("\n[Wnioski] Automatyczna weryfikacja hipotez H1-H5...")
    zapisz_wnioski(df, OUTPUT_DIR)
    print("  [OK] wnioski_hipotezy.md")

    print("\n" + "=" * 74)
    print("  ANALIZA ZAKONCZONA")
    print(f"  Otworz folder: {OUTPUT_DIR}/")
    print("=" * 74)


if __name__ == "__main__":
    try:
        main()
    except requests.exceptions.HTTPError as e:
        print(f"\n[BLAD HTTP] {e.response.status_code}")
        print(f"   Response: {e.response.text[:500]}")
        sys.exit(1)
    except requests.exceptions.ConnectionError:
        print(f"\n[BLAD POLACZENIA] Nie moge polaczyc z {BASE_URL}")
        sys.exit(1)
    except KeyboardInterrupt:
        print("\n[Przerwane przez uzytkownika]")
        sys.exit(130)
