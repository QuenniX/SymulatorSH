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

import csv
import glob

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib as mpl
import requests

import taryfy as T

# ============================================================================
#  KONFIGURACJA
# ============================================================================

BASE_URL = "http://3.77.28.199/api/v1"
# BASE_URL = "http://localhost:8080/api/v1"

KEY_FILE = "api_key.txt"
BATCH_NAME_PREFIX = "[Partia 2026-08-18T09:38]"
OUTPUT_DIR = "wyniki_analizy"

# --- Sezony meteorologiczne 2025 (środek każdego sezonu, 30 dni) ---
SEASONS = {
    "Zima":   (date(2025, 1, 1),  date(2025, 1, 30)),
    "Wiosna": (date(2025, 4, 1),  date(2025, 4, 30)),
    "Lato":   (date(2025, 7, 1),  date(2025, 7, 30)),
    "Jesien": (date(2025, 10, 1), date(2025, 10, 30)),
}
SEASON_ORDER = ["Zima", "Wiosna", "Lato", "Jesien"]

# --- Taryfa 2026: parametry pochodza z modulu taryfy.py ---
# Stawki zlozone ze skladnikow taryf zatwierdzonych przez Prezesa URE:
#   PGE Obrot S.A.,      taryfa dla grup G, od 1 I 2026    - cena energii czynnej
#   PGE Dystrybucja S.A., taryfa na 2026, tekst jednolity   - stawki sieciowe i strefy
# Rok 2025 mial tarcze mrozeniowa (G11=0,75), ktora zanizala G11 sztucznie; testy
# uruchomiono w 2026, wiec model uzywa parametrow 2026 - pierwszego roku bez tarczy.
G11_PLN_KWH = T.G11                  # 1,0991
G12_DAY_PLN_KWH = T.G12_DZIEN        # 1,2491
G12_NIGHT_PLN_KWH = T.G12_NOC        # 0,6111
VAT_MULTIPLIER = T.VAT

# Narzut taryfy dynamicznej: odbiorca RDN jest rozliczany dystrybucyjnie w grupie
# G11, ponosi wiec te same stawki sieciowe i systemowe; roznica dotyczy wylacznie
# skladnika energii. Marza sprzedawcy jest jedynym parametrem swobodnym.
RDN_NARZUT_NET = T.NARZUT            # 0,4954 = 0,3469 + 0,0485 + marza 0,10

# Zrodlo danych: "pliki" = katalog dane_zrodlowe (zrzut z eksport_danych.py),
# "api" = odpytanie produkcji. Tryb plikowy jest domyslny, bo nie wymaga
# dzialajacej instancji EC2 i gwarantuje powtarzalnosc wynikow w pracy.
ZRODLO_DANYCH = "pliki"
DANE_ZRODLOWE = "dane_zrodlowe"

# --- Profile i sezony harmonogramowe (z partii user'a) ---
PROFILES = {
    "A": "Singiel-biuro",
    "B": "Pracownik zdalny",
    "C": "Rodzina 2+2",
    "D": "Senior samotny",
    "E": "Studenci",
    "F": "Para bez dzieci",
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
    """Cena detaliczna taryfy dynamicznej: (hurt + narzut) × VAT."""
    return (wholesale_pln_kwh + RDN_NARZUT_NET) * VAT_MULTIPLIER


def g12_price_for_hour(hour: int, sezon: str) -> float:
    """
    Cena G12 dla godziny doby. Strefy sa SEZONOWE - okno popoludniowe strefy
    tanszej to 13:00-15:00 w okresie zimowym (1 X - 31 III) i 15:00-17:00
    w letnim (1 IV - 30 IX), zgodnie z taryfa PGE Dystrybucja dla grup C12b/G12.
    Decyduje sezon CENOWY, bo to on wyznacza date kalendarzowa zuzycia.
    """
    return T.g12_cena(hour, sezon)


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
    sezon_cen: str,
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
            # NIE uzywac .get(h, 0.0): brakujaca cena dawala 0 zl/kWh, czyli DARMOWA
            # energie w tej godzinie, co po cichu zanizalo koszt RDN. Lepiej wywalic sie
            # glosno niz oddac wynik, ktory wyglada poprawnie.
            if h not in hour_prices:
                raise ValueError(
                    f"Brak ceny RDN dla {day} godz. {h:02d}. Uzupelnij backfill PSE "
                    f"(POST /prices/backfill) albo zawez okno sezonu."
                )
            price_rdn = hour_prices[h]
            day_rdn += kwh * price_rdn
            day_g11 += kwh * G11_PLN_KWH
            day_g12 += kwh * g12_price_for_hour(h, sezon_cen)
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

def _zbuduj_wiersze(
    profiles: Dict[str, np.ndarray],
    seasonal_prices: Dict[str, Dict[str, Dict[int, float]]],
) -> pd.DataFrame:
    """Wspolna re-kalkulacja: 24 testy × 4 sezony cenowe = 96 wynikow."""
    rows = []
    for key, profile in profiles.items():
        code, sezon_harm = key.split("_")
        arch_label = PROFILES.get(code, code)
        for sezon_cen in SEASON_ORDER:
            sc = recalculate_costs(profile, seasonal_prices[sezon_cen], sezon_cen)
            saving_vs_g11 = ((sc.cost_g11 - sc.cost_rdn) / sc.cost_g11 * 100) if sc.cost_g11 > 0 else 0
            saving_vs_g12 = ((sc.cost_g12 - sc.cost_rdn) / sc.cost_g12 * 100) if sc.cost_g12 > 0 else 0
            rows.append({
                "profil_kod": code,
                "profil_nazwa": arch_label,
                "sezon_harmonogram": sezon_harm,
                "sezon_ceny": sezon_cen,
                "udzial_strefy_nocnej_pct": round(T.udzial_nocny(list(profile), sezon_cen) * 100, 2),
                "koszt_G11_pln": round(sc.cost_g11, 2),
                "koszt_G12_pln": round(sc.cost_g12, 2),
                "koszt_RDN_pln": round(sc.cost_rdn, 2),
                "roznica_RDN_vs_G11_pct": round(saving_vs_g11, 2),
                "roznica_RDN_vs_G12_pct": round(saving_vs_g12, 2),
                "VaR95_RDN_pln": round(sc.var_95_rdn, 2),
                "CVaR95_RDN_pln": round(sc.cvar_95_rdn, 2),
                "dni_pokryte": sc.days_covered,
                "diagonal": sezon_harm == sezon_cen,
            })
    df = pd.DataFrame(rows)
    print(f"  [OK] Wygenerowano {len(df)} wierszy")
    return df


def gather_all_data_pliki() -> Tuple[pd.DataFrame, Dict[str, np.ndarray]]:
    """
    Wariant offline: dane z katalogu dane_zrodlowe (zrzut z eksport_danych.py).
    Nie wymaga dzialajacej instancji EC2 i daje powtarzalny wynik - to on jest
    podstawa liczb raportowanych w pracy.
    """
    kat = os.path.join(os.path.dirname(os.path.abspath(__file__)), DANE_ZRODLOWE)
    if not os.path.isdir(kat):
        raise SystemExit(f"[BLAD] Brak katalogu {kat}. Uruchom najpierw eksport_danych.py")

    print(f"\n[1/3] Wczytuje profile godzinowe z {DANE_ZRODLOWE}/profile/ ...")
    profiles: Dict[str, np.ndarray] = {}
    for sciezka in sorted(glob.glob(os.path.join(kat, "profile", "*.csv"))):
        klucz = os.path.splitext(os.path.basename(sciezka))[0]      # np. "A_Zima"
        sumy, licz = np.zeros(24), np.zeros(24)
        with open(sciezka, encoding="utf-8-sig", newline="") as f:
            for r in csv.DictReader(f):
                h = datetime.fromisoformat(r["hour"]).hour
                sumy[h] += float(r["kwh"])
                licz[h] += 1
        if licz.min() == 0:
            raise SystemExit(f"[BLAD] {sciezka}: brak danych dla godziny {int(licz.argmin())}")
        godzin = int(licz.sum())
        if godzin != 720:
            print(f"    [!!] {klucz}: {godzin} godzin zamiast 720 ({godzin / 720:.1%} pokrycia)")
        profiles[klucz] = sumy / licz
    print(f"  [OK] Wczytano {len(profiles)} profili dobowych")
    if len(profiles) != 24:
        print(f"  [WARN] Oczekiwano 24 profili, jest {len(profiles)}")

    print(f"\n[2/3] Wczytuje ceny hurtowe z {DANE_ZRODLOWE}/ceny/wszystkie.csv ...")
    seasonal_prices: Dict[str, Dict[str, Dict[int, float]]] = defaultdict(lambda: defaultdict(dict))
    plik_cen = os.path.join(kat, "ceny", "wszystkie.csv")
    with open(plik_cen, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            hurt_kwh = float(str(r["cena_pln_mwh"]).replace(",", ".")) / 1000.0
            seasonal_prices[r["sezon"]][str(r["data"])[:10]][int(r["godz"])] = rdn_final_price(hurt_kwh)
    for sezon, (from_d, to_d) in SEASONS.items():
        by_day = seasonal_prices.get(sezon, {})
        oczekiwane = (to_d - from_d).days + 1
        if len(by_day) != oczekiwane:
            raise ValueError(f"{sezon}: {len(by_day)} dob zamiast {oczekiwane}")
        niepelne = {d: sorted(set(range(24)) - set(hp)) for d, hp in by_day.items() if len(hp) != 24}
        if niepelne:
            raise ValueError(f"{sezon}: doby z brakujacymi godzinami -> {niepelne}")
        print(f"    {sezon:7s}: {len(by_day)} dob x 24 godziny  [OK]")

    print("\n[3/3] Re-kalkulacja: 24 testy x 4 sezony cenowe = 96 wynikow...")
    return _zbuduj_wiersze(profiles, seasonal_prices), profiles


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
        # 30 dob x 24 godziny = 720. Mniej oznacza, ze czesc pomiarow nie dojechala
        # do InfluxDB (rozlaczenie MQTT / rate limit / retencja) albo wypadla poza
        # okno `range` w zapytaniu Flux. Profil dobowy da sie z tego policzyc, ale
        # totalKwh i liczba pomiarow w pracy beda zanizone - lepiej wiedziec od razu.
        if len(hourly) != 720:
            print(f"      [!!] {code}_{sezon}: {len(hourly)} godzin zamiast 720 "
                  f"({len(hourly) / 720:.1%} pokrycia) - sprawdz logi backendu")
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

        # Kompletnosc: oczekujemy 30 dob x 24 godziny. Dziury byly dotad niewidoczne,
        # bo brakujaca godzina schodzila do ceny 0 zl. Zmiana czasu 26.10.2025 wypada
        # w oknie sezonu Jesien - PSE zwraca wtedy 100 kwadransow, a PseApiClient
        # mapuje oba bloki 02:00 na godzine 2 (usrednia 8 kwadransow w jedna cene).
        expected_days = (to_d - from_d).days + 1
        if len(by_day) != expected_days:
            raise ValueError(f"{season_name}: {len(by_day)} dni zamiast {expected_days}")
        niepelne = {d: sorted(set(range(24)) - set(hp)) for d, hp in by_day.items()
                    if len(hp) != 24}
        if niepelne:
            raise ValueError(f"{season_name}: doby z brakujacymi godzinami -> {niepelne}")
        print(f"      [OK] kompletnosc {expected_days} dni x 24 godziny")

    print("\n[4/4] Re-kalkulacja: 24 testy x 4 sezony cenowe = 96 wynikow...")
    return _zbuduj_wiersze(profiles, seasonal_prices), profiles


# ============================================================================
#  WYKRESY MATPLOTLIB (6 głównych + 1 sensitivity)
# ============================================================================

def wykres_1_heatmap_diagonal(df: pd.DataFrame, out_path: str) -> None:
    """Heatmap 6×4: profil × sezon (tryb diagonal - harmonogram=ceny)."""
    diag = df[df["diagonal"]].copy()
    pivot = diag.pivot_table(index="profil_kod", columns="sezon_harmonogram",
                              values="roznica_RDN_vs_G11_pct", aggfunc="mean")
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
    ax.set_title("Wykres 1: Roznica kosztu RDN wzgledem G11 [%] - tryb diagonalny\n"
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
    cbar = fig.colorbar(im, ax=ax, label="Roznica kosztu [%]   dodatnie = RDN tansza, ujemne = RDN drozsza")
    cbar.ax.tick_params(labelsize=9)
    plt.tight_layout()
    plt.savefig(out_path)
    plt.close()


def wykres_2_ranking_profilow(df: pd.DataFrame, out_path: str) -> None:
    """Ranking profili - średnia różnica kosztu z 4 sezonów (diagonal)."""
    diag = df[df["diagonal"]].copy()
    # barh rysuje pierwszy element na DOLE, wiec sortowanie rosnace stawia
    # najkorzystniejszy profil na gorze - zgodnie z konwencja czytania rankingu.
    ranking = diag.groupby("profil_kod")["roznica_RDN_vs_G11_pct"].mean().sort_values(ascending=True)

    fig, ax = plt.subplots(figsize=(11, 6))
    labels = [f"{k}: {PROFILES[k]}" for k in ranking.index]
    colors = ["#d73027" if v < 0 else "#1a9850" for v in ranking.values]
    bars = ax.barh(labels, ranking.values, color=colors, edgecolor="black")
    ax.axvline(0, color="black", linewidth=1.0)
    ax.set_xlabel("Srednia roznica kosztu RDN wzgledem G11 [%] (po 4 sezonach)", fontsize=11)
    ax.set_title("Wykres 2: Ranking profilow gospodarstw domowych\n(od najkorzystniejszego u gory; dodatnie = RDN tansza od G11)", fontsize=12)
    # Zakres osi dobierany do danych, a nie symetrycznie wzgledem zera - przy
    # samych wartosciach ujemnych symetria marnowala polowe szerokosci wykresu
    # i wpychala etykiete najdluzszego slupka na opisy osi Y.
    lo = min(0.0, float(ranking.min()))
    hi = max(0.0, float(ranking.max()))
    pad = (hi - lo) * 0.22 or 1.0
    ax.set_xlim(lo - pad, hi + pad)
    for bar, v in zip(bars, ranking.values):
        offset = (hi - lo) * 0.02
        ax.text(v + (offset if v >= 0 else -offset), bar.get_y() + bar.get_height() / 2,
                f"{v:+.1f}%", va="center",
                ha="left" if v >= 0 else "right",
                fontweight="bold", fontsize=10)
    plt.tight_layout()
    plt.savefig(out_path)
    plt.close()


def wykres_3_boxplot_sezony(df: pd.DataFrame, out_path: str) -> None:
    """Box plot: rozkład różnicy kosztu per sezon cenowy (wszystkie 96 punktów)."""
    fig, ax = plt.subplots(figsize=(9, 5.5))
    data = [df[df["sezon_ceny"] == s]["roznica_RDN_vs_G11_pct"].values for s in SEASON_ORDER]
    bp = ax.boxplot(data, labels=SEASON_ORDER, patch_artist=True, showmeans=True,
                     meanprops={"marker": "D", "markerfacecolor": "red", "markersize": 8})
    palette = ["#3498db", "#2ecc71", "#f39c12", "#e67e22"]
    for patch, color in zip(bp["boxes"], palette):
        patch.set_facecolor(color)
        patch.set_alpha(0.6)
    ax.axhline(0, color="black", linewidth=0.8, linestyle="--")
    ax.set_ylabel("Roznica kosztu RDN wzgledem G11 [%]")
    ax.set_xlabel("Sezon cenowy")
    ax.set_title("Wykres 3: Rozklad roznicy kosztu per sezon cenowy\n"
                 "(6 profilow x 4 harmonogramy = 24 punkty per sezon; czerwony romb = srednia)")
    plt.tight_layout()
    plt.savefig(out_path)
    plt.close()


def wykres_4_scatter_savings_vs_risk(df: pd.DataFrame, out_path: str) -> None:
    """Scatter: różnica kosztu vs CVaR (trade-off zysk/ryzyko)."""
    diag = df[df["diagonal"]].copy()
    fig, ax = plt.subplots(figsize=(9, 6.5))
    palette = {"Zima": "#3498db", "Wiosna": "#2ecc71", "Lato": "#f39c12", "Jesien": "#e67e22"}
    for sezon in SEASON_ORDER:
        sub = diag[diag["sezon_ceny"] == sezon]
        ax.scatter(sub["CVaR95_RDN_pln"], sub["roznica_RDN_vs_G11_pct"],
                   s=140, color=palette[sezon], edgecolor="black", alpha=0.85, label=sezon)
        for _, row in sub.iterrows():
            ax.annotate(row["profil_kod"], (row["CVaR95_RDN_pln"], row["roznica_RDN_vs_G11_pct"]),
                        xytext=(6, 6), textcoords="offset points", fontsize=9)
    ax.axhline(0, color="black", linewidth=0.8, linestyle="--")
    ax.set_xlabel("CVaR 95% dziennego kosztu RDN [zl] (im wiecej, tym wyzsze ryzyko)")
    ax.set_ylabel("Roznica kosztu RDN wzgledem G11 [%]")
    ax.set_title("Wykres 4: Trade-off zysk vs ryzyko dla 24 przypadkow diagonalnych\n"
                 "(prawy gorny rog = korzystny wynik przy wysokim ryzyku dnia drogiego)")
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
    ranking = diag.groupby("sezon_ceny")["roznica_RDN_vs_G11_pct"].mean().reindex(SEASON_ORDER)

    fig, ax = plt.subplots(figsize=(9, 6))
    colors = ["#3498db", "#2ecc71", "#f39c12", "#e67e22"]
    bars = ax.bar(ranking.index, ranking.values, color=colors, edgecolor="black")
    ax.axhline(0, color="black", linewidth=1.0)
    ax.set_ylabel("Srednia roznica kosztu RDN wzgledem G11 [%]", fontsize=11)
    ax.set_xlabel("Sezon cenowy", fontsize=11)
    ax.set_title("Wykres 6: Ranking sezonow cenowych\n(usredniona roznica kosztu z 6 profilow diagonalnych)", fontsize=12)
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
                             values="roznica_RDN_vs_G11_pct", aggfunc="mean")
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
    ax.set_title("Wykres 7 (sensitivity): Roznica kosztu RDN wzgledem G11 [%] - 96 kombinacji\n"
                 "(wiersz = profil+harmonogram, kolumna = sezon cenowy)", fontsize=12)
    for i in range(pivot.shape[0]):
        for j in range(pivot.shape[1]):
            v = pivot.values[i, j]
            if not np.isnan(v):
                color = "white" if v < -vmax * 0.55 else "black"
                ax.text(j, i, f"{v:+.0f}", ha="center", va="center",
                        color=color, fontsize=9, fontweight="bold")
    cbar = fig.colorbar(im, ax=ax, label="Roznica kosztu [%]   dodatnie = RDN tansza")
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
            "udzial_strefy_nocnej_pct",
            "koszt_G11_pln", "koszt_G12_pln", "koszt_RDN_pln",
            "roznica_RDN_vs_G11_pct", "roznica_RDN_vs_G12_pct",
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
             "roznica_RDN_vs_G11_pct", "roznica_RDN_vs_G12_pct"]
        ].mean().reset_index()
        f.write(agg_arch.to_markdown(index=False, floatfmt=".2f"))
        f.write("\n\n### Średnia per sezon cenowy (diagonal)\n\n")
        agg_sezon = diag.groupby("sezon_harmonogram")[
            ["koszt_G11_pln", "koszt_G12_pln", "koszt_RDN_pln",
             "roznica_RDN_vs_G11_pct", "roznica_RDN_vs_G12_pct"]
        ].mean().reset_index()
        f.write(agg_sezon.to_markdown(index=False, floatfmt=".2f"))
        # Grand total
        f.write("\n\n### Podsumowanie ogólne (diagonal)\n\n")
        f.write(f"- **Średni koszt G11:** {diag['koszt_G11_pln'].mean():.2f} zł\n")
        f.write(f"- **Średni koszt G12:** {diag['koszt_G12_pln'].mean():.2f} zł\n")
        f.write(f"- **Średni koszt RDN:** {diag['koszt_RDN_pln'].mean():.2f} zł\n")
        f.write(f"- **Średnia różnica kosztu RDN vs G11:** {diag['roznica_RDN_vs_G11_pct'].mean():.2f}%\n")
        f.write(f"- **Średnia różnica kosztu RDN vs G12:** {diag['roznica_RDN_vs_G12_pct'].mean():.2f}%\n")

        # Średnia z procentów nie równa się procentowi ze średnich (efekt Simpsona):
        # pierwsza traktuje każde gospodarstwo jednakowo, druga waży je zużyciem.
        # W pracy raportowane są obie, bo odpowiadają na różne pytania.
        s11 = diag["koszt_G11_pln"].sum()
        s12 = diag["koszt_G12_pln"].sum()
        srdn = diag["koszt_RDN_pln"].sum()
        f.write("\n### Ujęcie kwotowe (iloraz średnich, ważone zużyciem)\n\n")
        f.write(f"- **Suma kosztów:** G11 = {s11:.2f} zł, G12 = {s12:.2f} zł, RDN = {srdn:.2f} zł\n")
        f.write(f"- **RDN vs G11:** {(s11 - srdn) / s11 * 100:.2f}% "
                f"(średnia z procentów: {diag['roznica_RDN_vs_G11_pct'].mean():.2f}%)\n")
        f.write(f"- **RDN vs G12:** {(s12 - srdn) / s12 * 100:.2f}% "
                f"(średnia z procentów: {diag['roznica_RDN_vs_G12_pct'].mean():.2f}%)\n")
        f.write(f"- **G12 vs G11:** {(s11 - s12) / s11 * 100:.2f}%\n")

        # Kontrola spójności: warunek analityczny 'udział strefy nocnej < próg'
        # musi pokrywać się z faktem 'G11 tańsza od G12'. Rozbieżność = błąd modelu.
        prog = T.prog_udzialu_nocnego() * 100
        ponizej = set(diag.loc[diag["udzial_strefy_nocnej_pct"] < prog, "profil_kod"]
                      + "-" + diag.loc[diag["udzial_strefy_nocnej_pct"] < prog, "sezon_harmonogram"])
        tansze = set(diag.loc[diag["koszt_G11_pln"] < diag["koszt_G12_pln"], "profil_kod"]
                     + "-" + diag.loc[diag["koszt_G11_pln"] < diag["koszt_G12_pln"], "sezon_harmonogram"])
        f.write(f"\n### Próg opłacalności G12\n\n")
        f.write(f"- **Próg udziału strefy nocnej:** τ = {prog:.2f}%\n")
        f.write(f"- **Próg ceny hurtowej dla RDN vs G11:** {T.prog_ceny_hurtowej():.0f} zł/MWh\n")
        f.write(f"- **Przypadki, w których G11 jest tańsza od G12:** "
                f"{', '.join(sorted(tansze)) if tansze else 'brak'}\n")
        f.write(f"- **Kontrola:** zbiór przewidziany progiem {'zgodny' if ponizej == tansze else 'ROZBIEŻNY'}"
                f" ze zbiorem wyznaczonym kosztowo\n")

        # Przypadki lezace tak blisko progu, ze o wyniku decyduja grosze. Podawanie
        # ich jako rozstrzygnietych bylo by nadinterpretacja - roznica kosztu jest
        # mniejsza niz niepewnosc samego modelu zuzycia.
        blisko = diag[(diag["udzial_strefy_nocnej_pct"] - prog).abs() < 1.0]
        if not blisko.empty:
            f.write("\n### Przypadki nierozstrzygnięte (udział strefy nocnej w granicach ±1 p.p. od progu)\n\n")
            f.write("| przypadek | udział strefy nocnej | G11 | G12 | różnica |\n")
            f.write("|:---|---:|---:|---:|---:|\n")
            for _, r in blisko.iterrows():
                d = r["koszt_G12_pln"] - r["koszt_G11_pln"]
                f.write(f"| {r['profil_kod']}-{r['sezon_harmonogram']} "
                        f"| {r['udzial_strefy_nocnej_pct']:.2f}% "
                        f"| {r['koszt_G11_pln']:.2f} zł | {r['koszt_G12_pln']:.2f} zł "
                        f"| {d:+.2f} zł |\n")
            f.write("\nW tych przypadkach wskazanie tańszej taryfy nie ma znaczenia praktycznego "
                    "i nie powinno być raportowane jako rozstrzygnięcie.\n")


def zapisz_wnioski(df: pd.DataFrame, out_dir: str) -> None:
    """Automatyczna weryfikacja hipotez H1-H5."""
    diag = df[df["diagonal"]].copy()

    with open(os.path.join(out_dir, "wnioski_hipotezy.md"), "w", encoding="utf-8") as f:
        f.write("# Automatyczna weryfikacja hipotez badawczych\n\n")
        f.write("_Wygenerowane przez analiza_wyniki.py na podstawie 24 testów baseline._\n\n")
        f.write("Konwencja znaku: wartość dodatnia oznacza, że taryfa dynamiczna jest **tańsza** "
                "od taryfy odniesienia, ujemna - że jest **droższa**.\n\n")
        f.write(f"Parametry: G11 = {G11_PLN_KWH:.4f}, G12 = {G12_DAY_PLN_KWH:.4f}/{G12_NIGHT_PLN_KWH:.4f} zł/kWh, "
                f"narzut RDN = {RDN_NARZUT_NET:.4f} zł/kWh (marża sprzedawcy {T.MARZA:.2f} zł/kWh).\n\n")

        # Kazda hipoteza raportowana w dwoch ujeciach: srednia z procentow traktuje
        # kazde gospodarstwo jednakowo, iloraz srednich wazy je zuzyciem (efekt
        # Simpsona). Podawanie tylko jednej z nich zaciemnia obraz.
        s11, s12, srdn = (diag["koszt_G11_pln"].sum(), diag["koszt_G12_pln"].sum(),
                          diag["koszt_RDN_pln"].sum())
        kw11 = (s11 - srdn) / s11 * 100
        kw12 = (s12 - srdn) / s12 * 100

        # H1: RDN opłacalny średnio
        avg = diag["roznica_RDN_vs_G11_pct"].mean()
        f.write(f"## H1: RDN jest średnio opłacalny wobec G11 dla polskich gospodarstw\n\n")
        f.write(f"**Wynik:** średnia z procentów = **{avg:.2f}%**, ujęcie kwotowe = **{kw11:.2f}%**.\n")
        f.write(f"**Weryfikacja:** {'POTWIERDZONA' if avg > 0 and kw11 > 0 else 'NIEPOTWIERDZONA'} "
                f"(próg: >0% w obu ujęciach).\n\n")

        # H2: RDN opłacalny > G12
        avg_g12 = diag["roznica_RDN_vs_G12_pct"].mean()
        f.write(f"## H2: RDN jest opłacalny również wobec G12 (dzień/noc)\n\n")
        f.write(f"**Wynik:** średnia z procentów = **{avg_g12:.2f}%**, ujęcie kwotowe = **{kw12:.2f}%**.\n")
        f.write(f"**Weryfikacja:** {'POTWIERDZONA' if avg_g12 > 0 and kw12 > 0 else 'NIEPOTWIERDZONA'}.\n\n")

        # H3: Opłacalność różni się między profilami
        by_arch = diag.groupby("profil_kod")["roznica_RDN_vs_G11_pct"].mean()
        best_arch = by_arch.idxmax()
        worst_arch = by_arch.idxmin()
        spread = by_arch.max() - by_arch.min()
        f.write(f"## H3: Wynik taryfy dynamicznej różni się istotnie między profilami\n\n")
        f.write(f"**Wynik:** Najlepszy profil = **{best_arch} ({PROFILES[best_arch]})** "
                f"({by_arch.max():.2f}%), najgorszy = **{worst_arch} ({PROFILES[worst_arch]})** "
                f"({by_arch.min():.2f}%). Rozstęp = **{spread:.2f} pp**.\n")
        f.write(f"**Weryfikacja:** {'POTWIERDZONA' if spread > 2 else 'NIEPOTWIERDZONA'} (próg: rozstęp > 2 pp).\n\n")

        # H4: Zima ma większą oszczędność niż lato (większe amplitudy cen)
        by_sezon = diag.groupby("sezon_harmonogram")["roznica_RDN_vs_G11_pct"].mean()
        zima = by_sezon.get("Zima", 0.0)
        lato = by_sezon.get("Lato", 0.0)
        f.write(f"## H4: Zimą taryfa dynamiczna wypada korzystniej niż latem (większe amplitudy cen)\n\n")
        f.write(f"**Wynik:** Zima = **{zima:.2f}%**, Lato = **{lato:.2f}%** (różnica: {zima - lato:.2f} pp).\n")
        f.write(f"**Weryfikacja:** {'POTWIERDZONA' if zima > lato else 'NIEPOTWIERDZONA'}.\n\n")

        # H5: Wnioski są robust (cross-season sensitivity)
        cross_by_arch = df.groupby("profil_kod")["roznica_RDN_vs_G11_pct"].mean()
        cross_best = cross_by_arch.idxmax()
        f.write(f"## H5: Ranking profili jest robust względem sezonu cenowego\n\n")
        f.write(f"**Wynik:** Diagonal - najlepszy = **{best_arch}**. Cross-season 96 punktów - najlepszy = **{cross_best}**.\n")
        f.write(f"**Weryfikacja:** {'POTWIERDZONA' if best_arch == cross_best else 'CZĘŚCIOWA (wynik zależy od okresu cen)'}\n\n")

        # Dodatkowe insighty
        f.write(f"## Dodatkowe obserwacje\n\n")
        f.write(f"- Najkorzystniejszy przypadek: **{diag['roznica_RDN_vs_G11_pct'].max():.2f}%** "
                f"({diag.loc[diag['roznica_RDN_vs_G11_pct'].idxmax(), 'profil_nazwa']} - "
                f"{diag.loc[diag['roznica_RDN_vs_G11_pct'].idxmax(), 'sezon_harmonogram']})\n")
        f.write(f"- Najmniej korzystny przypadek: **{diag['roznica_RDN_vs_G11_pct'].min():.2f}%** "
                f"({diag.loc[diag['roznica_RDN_vs_G11_pct'].idxmin(), 'profil_nazwa']} - "
                f"{diag.loc[diag['roznica_RDN_vs_G11_pct'].idxmin(), 'sezon_harmonogram']})\n")
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
    print(f"\n  Katalog wynikowy: {OUTPUT_DIR}/")
    print(f"  Zrodlo danych: {ZRODLO_DANYCH}")
    print(f"  Taryfy: G11 {G11_PLN_KWH:.4f} | G12 {G12_DAY_PLN_KWH:.4f}/{G12_NIGHT_PLN_KWH:.4f}"
          f" | narzut RDN {RDN_NARZUT_NET:.4f} (marza {T.MARZA:.2f}) zl/kWh")
    print(f"  Prog udzialu strefy nocnej: {T.prog_udzialu_nocnego() * 100:.2f}%"
          f" | prog ceny hurtowej: {T.prog_ceny_hurtowej():.0f} zl/MWh")

    if ZRODLO_DANYCH == "pliki":
        df, profiles = gather_all_data_pliki()
    else:
        print(f"\n  Endpoint: {BASE_URL}")
        print("\n[0/4] Autentykacja...")
        api_key = load_or_register_key()
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
