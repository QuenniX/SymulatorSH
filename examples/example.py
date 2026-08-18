"""
Przykladowy klient API SymulatorSH.

Pokazuje pelen cykl zycia badania przez API - bez uzywania interfejsu graficznego:
1. Rejestracja klucza API (raz - potem uzywasz zapisanego)
2. Pobranie listy gotowych szablonow (profilow gospodarstw)
3. Uruchomienie partii testow z jednym wywolaniem
4. Sledzenie postepu na zywo przez Server-Sent Events
5. Pobranie kosztow dla kazdego testu w partii
6. Porownanie testow (jedno wywolanie zamiast N)
7. Eksport wynikow do CSV

Wymagane biblioteki (jedna komenda instalacji):
    pip install requests

Uruchomienie:
    python example.py

Dla srodowiska produkcyjnego zmien BASE_URL na http://3.77.28.199
"""

import json
import os
import sys
import time
from typing import Optional

import requests


# ==========================================================================
#  Konfiguracja
# ==========================================================================

BASE_URL = "http://localhost:8080/api/v1"
# BASE_URL = "http://3.77.28.199/api/v1"  # produkcja na AWS EC2

# Klucz zapisany po pierwszym uruchomieniu - nie rejestrujemy za kazdym razem.
KEY_FILE = "api_key.txt"

# Parametry testu (male zeby demo bylo szybkie)
TEST_DURATION_DAYS = 1        # tylko 1 dzien symulacji
TEST_SPEED_FACTOR = 2160      # x2160 = ~40 sekund realnie
TEST_EMIT_EVERY_N = 10        # pomiar co 10 minut sym (mniej danych, szybciej)
NUM_TESTS_IN_BATCH = 3        # 3 rownolegle testy


# ==========================================================================
#  Klient API - cienkie wrappery na endpointy
# ==========================================================================

def register_key(name: str, email: str) -> dict:
    """POST /auth/register - dostajesz klucz jednorazowo."""
    r = requests.post(f"{BASE_URL}/auth/register",
                      json={"name": name, "email": email},
                      timeout=10)
    r.raise_for_status()
    return r.json()


def me(api_key: str) -> dict:
    """GET /auth/me - sprawdz swoj profil i limity."""
    r = requests.get(f"{BASE_URL}/auth/me",
                     headers={"X-API-Key": api_key},
                     timeout=10)
    r.raise_for_status()
    return r.json()


def list_templates(api_key: str) -> list:
    """GET /templates - lista wszystkich szablonow (profile + wlasne)."""
    r = requests.get(f"{BASE_URL}/templates",
                     headers={"X-API-Key": api_key},
                     timeout=10)
    r.raise_for_status()
    return r.json()


def create_batch(api_key: str, template_ids: list) -> dict:
    """POST /tests/batch - uruchamia N testow z jednego wywolania."""
    payload = {
        "templateIds": template_ids,
        "durationDays": TEST_DURATION_DAYS,
        "speedFactor": TEST_SPEED_FACTOR,
        "emitEveryNMinutes": TEST_EMIT_EVERY_N,
        "namePrefix": "[example.py]"
    }
    r = requests.post(f"{BASE_URL}/tests/batch",
                      headers={"X-API-Key": api_key, "Content-Type": "application/json"},
                      json=payload, timeout=30)
    r.raise_for_status()
    return r.json()


def stream_batch(api_key: str, batch_id: str, max_seconds: int = 300) -> None:
    """GET /batches/{batchId}/stream - subskrypcja SSE do momentu zakonczenia partii."""
    url = f"{BASE_URL}/batches/{batch_id}/stream"
    print(f"\n🔴 Subskrypcja stream {url}")
    print("   Czekam na updates (Ctrl+C zeby przerwac)...\n")

    start = time.time()
    with requests.get(url, headers={"X-API-Key": api_key}, stream=True, timeout=max_seconds) as r:
        for line in r.iter_lines():
            if not line:
                continue
            # SSE format: "data: <json>"
            if line.startswith(b"data:"):
                event = json.loads(line[5:].decode("utf-8"))
                completed = event.get("completed", 0)
                total = event.get("total", 0)
                running = event.get("running", 0)
                queued = event.get("queued", 0)
                failed = event.get("failed", 0)

                print(f"   📊 Batch: {completed}/{total} zrobione · "
                      f"{running} w trakcie · {queued} w kolejce · {failed} bledow")

                if completed + failed >= total:
                    print(f"\n✅ Partia ukonczona po {int(time.time() - start)}s\n")
                    return


def get_costs(api_key: str, test_id: str) -> dict:
    """GET /tests/{id}/costs - koszt w 3 taryfach."""
    r = requests.get(f"{BASE_URL}/tests/{test_id}/costs",
                     headers={"X-API-Key": api_key},
                     timeout=30)
    r.raise_for_status()
    return r.json()


def compare_tests(api_key: str, test_ids: list) -> dict:
    """POST /tests/compare - porownanie wielu testow jednym wywolaniem."""
    r = requests.post(f"{BASE_URL}/tests/compare",
                      headers={"X-API-Key": api_key, "Content-Type": "application/json"},
                      json={"testIds": test_ids}, timeout=60)
    r.raise_for_status()
    return r.json()


def export_csv(api_key: str, test_id: str, filename: str) -> int:
    """GET /tests/{id}/export?format=csv - zapisuje CSV do pliku."""
    r = requests.get(f"{BASE_URL}/tests/{test_id}/export",
                     params={"format": "csv"},
                     headers={"X-API-Key": api_key},
                     timeout=60)
    r.raise_for_status()
    with open(filename, "wb") as f:
        f.write(r.content)
    return len(r.content)


# ==========================================================================
#  Helper - zapisz/wczytaj klucz z pliku
# ==========================================================================

def load_or_register_key() -> str:
    """Wczytuje klucz z pliku albo rejestruje nowy jesli nie ma."""
    if os.path.exists(KEY_FILE):
        with open(KEY_FILE) as f:
            key = f.read().strip()
        print(f"📎 Wczytano zapisany klucz z {KEY_FILE}")
        return key

    print("🔑 Rejestracja nowego klucza API...")
    result = register_key(name="Przykladowy Badacz",
                          email="badacz@example.com")
    key = result["apiKey"]
    with open(KEY_FILE, "w") as f:
        f.write(key)
    print(f"   Zapisano klucz do {KEY_FILE}")
    print(f"   Limit dzienny: {result['dailyLimit']} testow")
    return key


# ==========================================================================
#  Glowna funkcja - pelen scenariusz demo
# ==========================================================================

def main() -> None:
    print("=" * 70)
    print("  SymulatorSH - przykladowy klient API")
    print("=" * 70)
    print(f"  Endpoint: {BASE_URL}\n")

    # === 1. Rejestracja klucza ===
    api_key = load_or_register_key()

    # === 2. Sprawdz profil ===
    profile = me(api_key)
    print(f"\n👤 Profil: {profile['userName']} ({profile['email']})")
    print(f"   Limit dzienny: {profile['dailyLimit']} testow")

    # === 3. Pobierz szablony ===
    templates = list_templates(api_key)
    print(f"\n📚 Dostepnych szablonow: {len(templates)}")

    # Weź szablony z "Zima" w nazwie - do naszego demo
    zima_templates = [t for t in templates if "Zima" in t.get("name", "")]
    if len(zima_templates) < NUM_TESTS_IN_BATCH:
        print(f"⚠️  Znaleziono tylko {len(zima_templates)} szablonow 'Zima', potrzebne {NUM_TESTS_IN_BATCH}")
        chosen = zima_templates or templates[:NUM_TESTS_IN_BATCH]
    else:
        chosen = zima_templates[:NUM_TESTS_IN_BATCH]

    print(f"   Wybrano {len(chosen)} szablonow do partii:")
    for t in chosen:
        print(f"     - {t['name']}")

    # === 4. Uruchom partie ===
    print(f"\n🚀 Uruchamianie partii testow ({TEST_DURATION_DAYS} dni, x{TEST_SPEED_FACTOR})...")
    batch = create_batch(api_key, [t["templateId"] for t in chosen])
    batch_id = batch["batchId"]
    test_ids = batch["createdTestIds"]
    print(f"   Batch ID: {batch_id}")
    print(f"   Utworzono {batch['createdCount']} test(ow), bledy: {batch['failedCount']}")

    # === 5. Sledzenie na zywo ===
    stream_batch(api_key, batch_id)

    # === 6. Pobierz koszty per test ===
    print("💰 Koszty per test:")
    print(f"   {'Nazwa':<45} {'G11':>8} {'G12':>8} {'RDN':>8}  Najtansza")
    print(f"   {'-'*45} {'-'*8} {'-'*8} {'-'*8}  ----------")
    for test_id in test_ids:
        try:
            costs = get_costs(api_key, test_id)
            name = next((t['name'] for t in chosen if t.get('name')), test_id[:8])
            g11 = float(costs.get('totalCostG11Pln', 0))
            g12 = float(costs.get('totalCostG12Pln', 0))
            rdn = float(costs.get('totalCostRdnPln', 0))
            cheapest = costs.get('cheapestTariff', '?')
            print(f"   {name[:45]:<45} {g11:>7.2f}zl {g12:>7.2f}zl {rdn:>7.2f}zl  {cheapest}")
        except Exception as e:
            print(f"   {test_id[:8]}...: BLAD - {e}")

    # === 7. Compare - porownanie wszystkich naraz ===
    print("\n📊 Porownanie testow (compare endpoint):")
    comparison = compare_tests(api_key, test_ids)
    summary = comparison.get("summary")
    if summary:
        print(f"   Najlepszy dla RDN:  {summary['bestForRdnName']}")
        print(f"                       oszczednosc RDN vs G11: {summary['bestForRdnSavingsPercent']}%")
        print(f"   Najgorszy dla RDN:  {summary['worstForRdnName']}")
        print(f"                       oszczednosc RDN vs G11: {summary['worstForRdnSavingsPercent']}%")
        print(f"   Srednia oszczednosc: {summary['avgSavingsPercent']}%")

    # === 8. Eksport pierwszego testu do CSV ===
    if test_ids:
        first_id = test_ids[0]
        filename = f"eksport_{first_id[:8]}.csv"
        size = export_csv(api_key, first_id, filename)
        print(f"\n💾 Eksport CSV: {filename} ({size} bajtow)")
        print(f"   Otworz w Excelu albo wczytaj do pandas:")
        print(f"     df = pd.read_csv('{filename}', sep=';', decimal=',')")

    print("\n" + "=" * 70)
    print("  ✅ Demo zakonczone. Klucz zapisany w api_key.txt do kolejnych uruchomien.")
    print("=" * 70)


if __name__ == "__main__":
    try:
        main()
    except requests.exceptions.HTTPError as e:
        print(f"\n❌ HTTP error: {e.response.status_code}")
        print(f"   Response: {e.response.text}")
        sys.exit(1)
    except requests.exceptions.ConnectionError:
        print(f"\n❌ Nie moge polaczyc sie z {BASE_URL}")
        print("   Sprawdz czy backend jest uruchomiony (mvn spring-boot:run albo IntelliJ Run)")
        sys.exit(1)
    except KeyboardInterrupt:
        print("\n⚠️  Przerwane przez uzytkownika")
        sys.exit(130)
