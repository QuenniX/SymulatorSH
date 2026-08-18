"""
================================================================================
  skrypt_testowy.py - Demonstracja API
================================================================================

Do prezentacji dla promotora. Pokazuje pelen cykl uzycia API w 5 krokach:

  1. Rejestracja klucza API                          (POST /auth/register)
  2. Pobranie listy dostepnych szablonow             (GET  /templates)
  3. Uruchomienie 3 testow rownolegle                (POST /tests/batch)
  4. Sledzenie postepu partii                        (GET  /batches/{id})
  5. Pobranie kosztow dla kazdego z 3 testow         (GET  /tests/{id}/costs)

Calosc trwa ~40-60 sekund. Testy 1-dniowe x2160 speed = ~40s per test,
ale 3 leca rownolegle wiec calosc ~40s.

Uruchomienie:
    pip install requests
    python skrypt_testowy.py
"""

import time
import requests

# ============================================================================
#  Konfiguracja
# ============================================================================

BASE_URL = "http://3.77.28.199/api/v1"
KEY_FILE = "api_key.txt"

# Parametry testu - krotkie zeby demo bylo szybkie
DURATION_DAYS = 1
SPEED_FACTOR = 2160        # 1 dzien symulacji = ~40 sekund realnie
EMIT_EVERY_N = 10          # pomiar co 10 min sym (mniej danych, szybciej)

# Ile szablonow wybrac z listy do partii demo
NUM_TESTS = 3


def sep(text=""):
    """Wyswietla wyrazny separator (do demonstracji)."""
    print()
    print("=" * 76)
    if text:
        print(f"  {text}")
        print("=" * 76)


def sleep_progress(seconds):
    """Pauzujaca sekundy z liczba - zeby promotor widzial ze cos sie dzieje."""
    for i in range(seconds, 0, -1):
        print(f"    Sprawdze za {i}s...", end="\r")
        time.sleep(1)
    print(" " * 40, end="\r")


# ============================================================================
#  Krok 1: Rejestracja klucza API
# ============================================================================

sep("KROK 1: Rejestracja klucza API  ->  POST /auth/register")

r = requests.post(f"{BASE_URL}/auth/register", json={
    "name": "Demo dla Promotora",
    "email": "demo@magisterka.pl"
})
r.raise_for_status()
api_key = r.json()["apiKey"]
daily_limit = r.json()["dailyLimit"]

print(f"  [OK] Zarejestrowano nowy klucz API")
print(f"       Klucz:  {api_key[:20]}... (skrocony dla bezpieczenstwa)")
print(f"       Limit:  {daily_limit} zapytan/dzien")

# Naglowek uzywany we wszystkich kolejnych zapytaniach
HEADERS = {"X-API-Key": api_key}


# ============================================================================
#  Krok 2: Pobranie listy szablonow
# ============================================================================

sep("KROK 2: Pobranie listy gotowych szablonow  ->  GET /templates")

r = requests.get(f"{BASE_URL}/templates", headers=HEADERS)
r.raise_for_status()
templates = r.json()

print(f"  [OK] Dostepnych szablonow: {len(templates)}")
print(f"       Pierwsze 5 przykladow:")
for t in templates[:5]:
    print(f"         - {t['name']}")

# Wybieramy 3 rozne profile do demo - z roznych sezonow zeby pokazac roznorodnosc
wanted = ["Singiel-biuro - Zima", "Rodzina 2+2 - Lato", "Senior samotny - Wiosna"]
chosen = []
for w in wanted:
    for t in templates:
        if w in t["name"]:
            chosen.append(t)
            break

print(f"\n  Wybrano {len(chosen)} profilow do demo:")
for t in chosen:
    print(f"         - {t['name']}")


# ============================================================================
#  Krok 3: Uruchomienie 3 testow jednym batchem
# ============================================================================

sep("KROK 3: Uruchomienie 3 testow rownolegle  ->  POST /tests/batch")

payload = {
    "templateIds": [t["templateId"] for t in chosen],
    "durationDays": DURATION_DAYS,
    "speedFactor": SPEED_FACTOR,
    "emitEveryNMinutes": EMIT_EVERY_N,
    "namePrefix": "[Demo Promotor]"
}

r = requests.post(f"{BASE_URL}/tests/batch", headers=HEADERS, json=payload)
r.raise_for_status()
batch = r.json()

batch_id = batch["batchId"]
test_ids = batch["createdTestIds"]

print(f"  [OK] Uruchomiono partie {NUM_TESTS} testow jednym zapytaniem")
print(f"       Batch ID:       {batch_id}")
print(f"       Utworzonych:    {batch['createdCount']}")
print(f"       Bledy:          {batch['failedCount']}")
print(f"       Parametry:      {DURATION_DAYS} dni symulacji, przyspieszenie x{SPEED_FACTOR}")


# ============================================================================
#  Krok 4: Sledzenie postepu partii
# ============================================================================

sep("KROK 4: Sledzenie postepu partii  ->  GET /batches/{id}")

print(f"  Odpytuje status partii co 5 sekund az wszystkie sie skoncza...")
print()

start = time.time()
while True:
    r = requests.get(f"{BASE_URL}/batches/{batch_id}", headers=HEADERS)
    r.raise_for_status()
    status = r.json()

    elapsed = int(time.time() - start)
    finished = status["completed"] + status["failed"] + status["cancelled"]
    total = status["total"]

    print(f"  [{elapsed:3d}s]  Ukonczone: {status['completed']}/{total}  |  "
          f"W trakcie: {status['running']}  |  W kolejce: {status['queued']}  |  "
          f"Bledy: {status['failed']}")

    if finished >= total:
        print(f"\n  [OK] Partia ukonczona po {elapsed} sekundach!")
        break

    sleep_progress(5)


# ============================================================================
#  Krok 5: Pobranie kosztow dla kazdego testu
# ============================================================================

sep("KROK 5: Pobranie kosztow  ->  GET /tests/{id}/costs")

print(f"  Dla kazdego z {NUM_TESTS} testow pobieram wyliczone koszty w 3 taryfach:\n")

# Naglowek tabeli
print(f"  {'Profil':<35} {'kWh':>8} {'G11 [zl]':>10} {'G12 [zl]':>10} {'RDN [zl]':>10}  {'Najtansza':>10}")
print(f"  {'-' * 35} {'-' * 8} {'-' * 10} {'-' * 10} {'-' * 10}  {'-' * 10}")

for i, test_id in enumerate(test_ids):
    r = requests.get(f"{BASE_URL}/tests/{test_id}/costs", headers=HEADERS)
    r.raise_for_status()
    c = r.json()

    name = chosen[i]["name"].replace("[Demo Promotor] ", "")[:35]
    kwh = float(c["totalKwh"])
    g11 = float(c["totalCostG11Pln"])
    g12 = float(c["totalCostG12Pln"])
    rdn = float(c["totalCostRdnPln"])
    cheapest = c.get("cheapestTariff", "?")

    print(f"  {name:<35} {kwh:>8.2f} {g11:>10.2f} {g12:>10.2f} {rdn:>10.2f}  {cheapest:>10}")



# ============================================================================
#  Podsumowanie
# ============================================================================

sep("DEMO ZAKONCZONE")
print(f"  Wszystkie zapytania wykonane pomyslnie przez publiczne API.")
print(f"  Dokumentacja API: http://3.77.28.199/swagger-ui/index.html")
print()
