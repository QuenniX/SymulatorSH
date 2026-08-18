# Przykładowe skrypty Python — klient API SymulatorSH

Folder zawiera przykłady użycia publicznego API SymulatorSH przez skrypty Python.
Pokazują, że platformę można w pełni obsługiwać **bez interfejsu graficznego** —
tak jak zapowiada [SPECYFIKACJA_API.md](../SPECYFIKACJA_API.md).

## `example.py` — demo pełnego cyklu życia badania

**Co robi (7 kroków):**

1. Rejestruje klucz API (raz — potem odczytuje z `api_key.txt`)
2. Sprawdza profil i limity (`GET /auth/me`)
3. Pobiera listę dostępnych szablonów (`GET /templates`)
4. Uruchamia partię 3 testów (`POST /tests/batch`)
5. Śledzi postęp na żywo przez Server-Sent Events (`GET /batches/{id}/stream`)
6. Pobiera koszty dla każdego testu (`GET /tests/{id}/costs`)
7. Robi porównanie i eksport pierwszego testu do CSV

**Wymagania:**

```bash
pip install requests
```

**Uruchomienie:**

```bash
# Upewnij się że backend działa na localhost:8080
python example.py
```

**Przykładowy output:**

```
======================================================================
  SymulatorSH - przykladowy klient API
======================================================================
  Endpoint: http://localhost:8080/api/v1

🔑 Rejestracja nowego klucza API...
   Zapisano klucz do api_key.txt
   Limit dzienny: 1000 testow

👤 Profil: Przykladowy Badacz (badacz@example.com)
   Limit dzienny: 1000 testow

📚 Dostepnych szablonow: 24
   Wybrano 3 szablonow do partii:
     - Profil A: Singiel-biuro - Zima
     - Profil B: Remote worker - Zima
     - Profil C: Rodzina 2+2 - Zima

🚀 Uruchamianie partii testow (1 dni, x2160)...
   Batch ID: abc-...
   Utworzono 3 test(ow), bledy: 0

🔴 Subskrypcja stream http://localhost:8080/api/v1/batches/abc-.../stream
   Czekam na updates (Ctrl+C zeby przerwac)...

   📊 Batch: 0/3 zrobione · 3 w trakcie · 0 w kolejce · 0 bledow
   📊 Batch: 1/3 zrobione · 2 w trakcie · 0 w kolejce · 0 bledow
   📊 Batch: 2/3 zrobione · 1 w trakcie · 0 w kolejce · 0 bledow
   📊 Batch: 3/3 zrobione · 0 w trakcie · 0 w kolejce · 0 bledow

✅ Partia ukonczona po 45s

💰 Koszty per test:
   Nazwa                                           G11      G12      RDN  Najtansza
   ---------------------------------------------  --------  --------  --------  ----------
   Profil A: Singiel-biuro - Zima              10.23zl   6.87zl   9.45zl  G12
   Profil B: Remote worker - Zima               8.11zl   6.02zl   7.34zl  G12
   Profil C: Rodzina 2+2 - Zima                18.45zl  15.20zl  17.80zl  G12

📊 Porownanie testow (compare endpoint):
   Najlepszy dla RDN:  Profil B: Remote worker - Zima
                       oszczednosc RDN vs G11: 9.5%
   Najgorszy dla RDN:  Profil C: Rodzina 2+2 - Zima
                       oszczednosc RDN vs G11: 3.5%
   Srednia oszczednosc: 6.7%

💾 Eksport CSV: eksport_abc12345.csv (1234 bajtow)
   Otworz w Excelu albo wczytaj do pandas:
     df = pd.read_csv('eksport_abc12345.csv', sep=';', decimal=',')

======================================================================
  ✅ Demo zakonczone. Klucz zapisany w api_key.txt do kolejnych uruchomien.
======================================================================
```

## Zmiana środowiska

Aby wykorzystać wersję produkcyjną na AWS EC2, edytuj `example.py`:

```python
# Zamień:
BASE_URL = "http://localhost:8080/api/v1"
# Na:
BASE_URL = "http://3.77.28.199/api/v1"
```

## Następne kroki

`example.py` jest podstawą pod bardziej rozbudowany skrypt:

- **`analiza_wyniki.py`** (planowany) — bierze wyniki 24 testów baseline
  (6 profili × 4 sezony), generuje 7 wykresów matplotlib
  gotowych do wklejenia w pracę magisterską, tabelę wyników
  i weryfikuje hipotezy H1-H5 opłacalności RDN.

## Techniczne szczegóły

- **Autentykacja**: nagłówek `X-API-Key`. Bez klucza limit 100/dzień per IP,
  z kluczem 1000/dzień.
- **Rate limiting**: nagłówki `X-RateLimit-*` w każdej odpowiedzi.
- **SSE**: format standardowy W3C (`data: <json>` + pusta linia). Klient
  Python używa `requests.get(url, stream=True).iter_lines()`.
- **Dokumentacja pełna**: `http://localhost:8080/swagger-ui.html`
