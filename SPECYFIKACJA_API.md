# Specyfikacja publicznego API — SymulatorSH

**Wersja dokumentu:** 1.0
**Autor:** Igor Guła (WEiI PRz)
**Data:** lipiec 2026
**Status:** Robocza — do implementacji w tygodniu 3 sierpnia

---

## Cel dokumentu

Ten plik zawiera pełną specyfikację publicznego API SymulatorSH, przyjętą na podstawie rozmowy po spotkaniu z promotorem. Promotor sformułował wymaganie:

> „System ma udostępniać publiczne API, dzięki któremu użytkownik zewnętrzny może odpalać testy programatycznie (skryptem Python), otrzymywać aktualizacje w czasie rzeczywistym i pobierać wyniki — bez korzystania z interfejsu graficznego aplikacji."

Analogia: API działa jak API giełdowe (Alpaca, Interactive Brokers, Binance) — wzorzec **submit → stream → fetch**. Klient wysyła zlecenie, słucha statusu na żywo, pobiera wyniki po zakończeniu.

## Zasady architektoniczne

1. **Warstwa techniczna (URL, kod, pola JSON) — po angielsku.** REST konwencja światowa, kompatybilność z bibliotekami i przykładami.
2. **Warstwa użytkownika (UI, dokumentacja Swagger, komunikaty błędów) — po polsku.** Zgodnie z uwagą promotora o „za trudnych słowach".
3. **API-first design.** Frontend React to tylko jeden z klientów API — skrypt Python badacza jest drugim, równoprawnym klientem.
4. **Skalowalność horyzontalna.** Backend bezstanowy w warstwie HTTP, kolejka testów w PostgreSQL — worker'zy można dodawać/usuwać dynamicznie.

---

## Obszar 1 — Lista endpointów

Wszystkie endpointy pod prefiksem `/api/v1/`. Wersja **v1** — bez planu zmian w ramach obecnej pracy.

### Autentykacja i konto (2)

| Metoda | Ścieżka | Opis |
|---|---|---|
| POST | `/api/v1/auth/register` | Rejestracja — dostajesz klucz dostępu |
| GET | `/api/v1/auth/me` | Sprawdź swój limit i zużycie dzisiaj |

### Testy pojedyncze (5)

| Metoda | Ścieżka | Opis |
|---|---|---|
| POST | `/api/v1/tests` | Uruchom pojedynczy test |
| GET | `/api/v1/tests` | Lista twoich testów (z filtrami) |
| GET | `/api/v1/tests/{id}` | Status pojedynczego testu |
| GET | `/api/v1/tests/{id}/stream` | Aktualizacje na żywo (SSE) |
| DELETE | `/api/v1/tests/{id}` | Anuluj test |

### Partie testów (4)

| Metoda | Ścieżka | Opis |
|---|---|---|
| POST | `/api/v1/tests/batch` | Uruchom partię N testów |
| GET | `/api/v1/batches/{batchId}` | Snapshot stanu partii |
| GET | `/api/v1/batches/{batchId}/stream` | Aktualizacje partii na żywo (SSE) |
| DELETE | `/api/v1/batches/{batchId}` | Anuluj całą partię |

### Dane i wyniki (4)

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET | `/api/v1/tests/{id}/costs` | Koszty G11/G12/RDN + statystyki ryzyka |
| GET | `/api/v1/tests/{id}/measurements` | Surowe pomiary (JSON) |
| GET | `/api/v1/tests/{id}/export?format=csv\|xlsx` | Eksport plikowy |
| POST | `/api/v1/tests/compare` | Porównanie wielu testów |

### Zasoby pomocnicze (4)

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET | `/api/v1/templates` | Lista szablonów profili |
| POST | `/api/v1/templates` | Utwórz własny szablon |
| DELETE | `/api/v1/templates/{id}` | Usuń szablon |
| GET | `/api/v1/prices?date=YYYY-MM-DD` | Ceny RDN na dany dzień |

**Suma: 19 endpointów.** Wszystkie oprócz `POST /auth/register` wymagają nagłówka `X-API-Key`.

---

## Obszar 2 — Autentykacja i klucze dostępu

### Model — prosta rejestracja + klucz stały

**Rejestracja bez weryfikacji email.** Użytkownik wysyła imię i email, natychmiast dostaje klucz dostępu.

```
POST /api/v1/auth/register
Content-Type: application/json

{
  "name": "Jan Kowalski",
  "email": "jan@example.com"
}
```

Odpowiedź (201 Created):

```json
{
  "apiKey": "sk_live_a4f8c2b1d9e73f6a...",
  "createdAt": "2026-08-15T14:32:00Z",
  "dailyLimit": 1000,
  "streamLimit": 20,
  "rateLimit": "10 req/sec"
}
```

**Ważne:** klucz wyświetlany **tylko raz** przy tworzeniu. W bazie trzymamy tylko `SHA256(klucz)` — nie da się go odzyskać. Jak użytkownik zgubi, rejestruje nowy.

### Nagłówek uwierzytelniający

Wszystkie kolejne zapytania wymagają:

```
X-API-Key: sk_live_a4f8c2b1d9e73f6a...
```

Brak nagłówka → HTTP 401 Unauthorized.
Nieznany klucz → HTTP 401 Unauthorized.
Klucz zablokowany → HTTP 403 Forbidden.

### Sprawdzenie limitów

```
GET /api/v1/auth/me
X-API-Key: sk_live_...
```

Odpowiedź:

```json
{
  "userName": "Jan Kowalski",
  "email": "jan@example.com",
  "createdAt": "2026-08-15T14:32:00Z",
  "usage": {
    "testsToday": 47,
    "testsRemaining": 953,
    "dailyLimit": 1000,
    "resetAt": "2026-08-16T00:00:00Z"
  },
  "activeStreams": 3,
  "streamLimit": 20
}
```

### Baza danych — nowa tabela

Migracja Flyway V4:

```sql
CREATE TABLE api_keys (
  id UUID PRIMARY KEY,
  key_hash VARCHAR(64) NOT NULL UNIQUE,  -- SHA256 hex
  user_name VARCHAR(255) NOT NULL,
  email VARCHAR(255) NOT NULL,
  daily_limit INT NOT NULL DEFAULT 1000,
  stream_limit INT NOT NULL DEFAULT 20,
  created_at TIMESTAMPTZ NOT NULL,
  last_used_at TIMESTAMPTZ,
  is_active BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX idx_api_keys_hash ON api_keys(key_hash);
CREATE INDEX idx_api_keys_email ON api_keys(email);
```

Do tego opcjonalnie tabela `api_key_usage` do audytu (można dodać później).

---

## Obszar 3 — Aktualizacje na żywo (Server-Sent Events)

### Dlaczego SSE, a nie WebSocket

- **Jednostronny stream** — serwer nadaje, klient słucha. Nie potrzebujemy dwustronnej komunikacji.
- **Prostota** — Spring Boot ma wbudowany `SseEmitter`, klient Python jednolinijkowy przez `requests` streaming.
- **Standard W3C** — każda biblioteka klienta go rozumie.

### Format eventów

Każdy event to linia zaczynająca się od `data:`, potem JSON:

```
data: {"testId":"abc-123","status":"RUNNING","progress":25,"message":"Dzień 7 z 30","timestamp":"2026-08-15T14:32:00Z"}
```

Standardowy schemat pól:

```json
{
  "testId": "abc-123",
  "status": "QUEUED | RUNNING | COMPLETED | FAILED | CANCELLED",
  "progress": 0-100,
  "message": "Czytelna wiadomość dla człowieka",
  "timestamp": "ISO 8601 UTC",
  "data": {
    "simulatedMinute": 10080,
    "totalMinutes": 43200,
    "elapsedRealSec": 458
  }
}
```

### Kiedy emitujemy

**Dla pojedynczego testu — 7 eventów:**
1. `QUEUED` — test wpadł do kolejki
2. `RUNNING progress=0` — TestExecutor bierze test
3. `RUNNING progress=25` — 25% postępu
4. `RUNNING progress=50` — 50% postępu
5. `RUNNING progress=75` — 75% postępu
6. `RUNNING progress=100` — koniec symulacji
7. `COMPLETED` (albo `FAILED`) — status końcowy

**Dla partii testów — event przy KAŻDEJ zmianie statusu któregokolwiek testu w partii.**

### Format eventu partii

```json
{
  "batchId": "batch-xyz-789",
  "total": 10,
  "queued": 5,
  "running": 4,
  "completed": 1,
  "failed": 0,
  "currentlyRunning": [
    {"testId": "abc-123", "name": "Studenci Zima", "progress": 45},
    {"testId": "def-456", "name": "DINK Lato",     "progress": 12},
    {"testId": "ghi-789", "name": "Senior Wiosna", "progress": 78},
    {"testId": "jkl-012", "name": "Rodzina Jesien","progress": 3}
  ],
  "timestamp": "2026-08-15T14:32:15Z"
}
```

Klient jednym zerkiem widzi cały stan partii.

### Keep-alive

Co **30 sekund** serwer wysyła pusty ping `:ping\n\n` żeby połączenie nie zostało uznane za zerwane przez proxy/NAT.

### Rozłączenie i ponowne podłączenie

Klient traci **wcześniejsze eventy**. Może:
- Ponownie podłączyć się do `GET /stream` — dostanie następne eventy
- Zapytać `GET /tests/{id}` (bez streamu) żeby zobaczyć aktualny stan

**Nie replikujemy wcześniejszych eventów** — prostsze rozwiązanie, wystarczające dla naszego use case.

### Limit równoczesnych połączeń

**20 równoczesnych SSE per klucz dostępu.** 21. dostaje HTTP 429 z wiadomością „Przekroczono limit równoczesnych strumieni".

---

## Obszar 4 — Format request / response głównych endpointów

### POST /api/v1/tests — pojedynczy test

**Request:**

```json
{
  "name": "Studenci Zima - mój test",
  "description": "Test z pompą ciepła dodatkowo",
  "durationDays": 30,
  "speedFactor": 720,
  "emitEveryNMinutes": 5,
  "devices": [
    {
      "id": "light_kitchen",
      "type": "LIGHT",
      "room": "KITCHEN",
      "powerW": 60,
      "schedule": [
        {"time": "07:00", "action": "ON"},
        {"time": "09:00", "action": "OFF"}
      ]
    }
  ]
}
```

**Response (201 Created):**

```json
{
  "testId": "abc-123-def-456",
  "status": "QUEUED",
  "createdAt": "2026-08-15T14:32:00Z",
  "estimatedDurationSec": 3600,
  "streamUrl": "/api/v1/tests/abc-123-def-456/stream",
  "links": {
    "self": "/api/v1/tests/abc-123-def-456",
    "costs": "/api/v1/tests/abc-123-def-456/costs",
    "cancel": "/api/v1/tests/abc-123-def-456"
  }
}
```

### POST /api/v1/tests/batch — partia testów

**Request:**

```json
{
  "namePrefix": "Baseline 2026-08",
  "durationDays": 30,
  "speedFactor": 720,
  "emitEveryNMinutes": 15,
  "templateIds": [
    "template-studenci-zima",
    "template-dink-zima",
    "template-senior-zima"
  ]
}
```

**Response (201 Created):**

```json
{
  "batchId": "batch-xyz-789",
  "totalTests": 3,
  "acceptedTests": 3,
  "rejectedTests": 0,
  "createdAt": "2026-08-15T14:32:00Z",
  "estimatedTotalDurationSec": 5400,
  "streamUrl": "/api/v1/batches/batch-xyz-789/stream",
  "tests": [
    {"testId": "abc-1", "name": "Baseline 2026-08 - Studenci Zima", "status": "QUEUED"},
    {"testId": "abc-2", "name": "Baseline 2026-08 - DINK Zima",     "status": "QUEUED"},
    {"testId": "abc-3", "name": "Baseline 2026-08 - Senior Zima",   "status": "QUEUED"}
  ],
  "errors": []
}
```

**Częściowy sukces:** jeśli któryś template nie istnieje, backend przyjmuje pozostałe testy i raportuje błędy w polu `errors`. Nie all-or-nothing.

### GET /api/v1/tests/{id}/costs — wyniki

**Response (200 OK):**

```json
{
  "testId": "abc-123",
  "period": {
    "from": "2026-08-01",
    "to": "2026-08-30",
    "daysInPeriod": 30
  },
  "energyKwh": {
    "total": 285.6,
    "avgDaily": 9.52
  },
  "costs": {
    "G11": {
      "totalPln": 214.20,
      "pricePerKwh": 0.75
    },
    "G12": {
      "totalPln": 202.15,
      "avgPricePerKwh": 0.71
    },
    "RDN": {
      "totalPln": 187.30,
      "avgPricePerKwh": 0.66
    }
  },
  "cheapestTariff": "RDN",
  "savings": {
    "rdnVsG11Percent": 12.6,
    "rdnVsG11Pln": 26.90,
    "g12VsG11Percent": 5.6
  },
  "risk": {
    "rdnDailyCost": {
      "min": 4.20,
      "median": 6.15,
      "max": 12.80,
      "var5Percent": 10.50,
      "cvar5Percent": 11.65
    }
  },
  "dataQuality": {
    "hoursWithFullPrices": 720,
    "hoursTotal": 720,
    "completeness": 1.0
  }
}
```

### GET /api/v1/tests/{id}/export?format=csv

**Response:**

```
Content-Type: text/csv
Content-Disposition: attachment; filename="test-abc-123.csv"

hour,kwh,cost_g11,cost_g12,cost_rdn,rdn_price_hourly
2026-08-01T00:00:00Z,0.42,0.315,0.176,0.187,0.446
2026-08-01T01:00:00Z,0.38,0.285,0.160,0.152,0.400
...
```

XLSX podobnie, z arkuszami: `Godziny`, `Podsumowanie`, `Konfiguracja testu`.

### POST /api/v1/tests/compare — porównanie

**Request:**

```json
{
  "testIds": ["abc-1", "abc-2", "abc-3"],
  "metrics": ["costs", "energy", "risk"]
}
```

**Response:**

```json
{
  "comparedTests": 3,
  "comparison": [
    {
      "testId": "abc-1",
      "name": "Studenci Zima",
      "totalCostG11": 214.20,
      "totalCostRdn": 187.30,
      "savingsPercent": 12.6,
      "cvar5Percent": 11.65
    },
    ...
  ],
  "summary": {
    "bestForRdn": "abc-1",
    "worstForRdn": "abc-3",
    "avgSavingsPercent": 6.3
  }
}
```

### Format błędów — jednolity dla wszystkich endpointów

```json
HTTP 4xx / 5xx
{
  "error": {
    "code": "INVALID_TEMPLATE_ID",
    "message": "Szablon o ID 'xxx' nie istnieje.",
    "statusCode": 404,
    "timestamp": "2026-08-15T14:32:00Z",
    "details": {
      "templateId": "xxx"
    }
  }
}
```

Kody błędów (lista otwarta, dodajemy w trakcie implementacji):
- `UNAUTHORIZED` (401) — brak lub nieznany klucz
- `FORBIDDEN` (403) — klucz zablokowany
- `NOT_FOUND` (404) — zasób nie istnieje
- `VALIDATION_ERROR` (400) — nieprawidłowy request
- `RATE_LIMIT_EXCEEDED` (429) — przekroczony limit
- `INTERNAL_ERROR` (500) — awaria backendu

---

## Obszar 5 — Wersjonowanie i dokumentacja

### Wersjonowanie

- Prefix `/api/v1/` na wszystkich endpointach.
- **v1 obowiązuje na zawsze** dla obecnej pracy.
- W przypadku zmian łamiących kompatybilność w przyszłości — tworzymy `/api/v2/`, v1 działa dalej.

### Dokumentacja OpenAPI / Swagger UI

Backend już ma zależność `springdoc-openapi`. Trzeba dopisać opisy w polskim do wszystkich endpointów przez adnotacje `@Operation`:

```java
@Operation(
    summary = "Uruchamia partię testów",
    description = """
        Tworzy N testów jednocześnie na podstawie zestawu szablonów.
        Wszystkie testy trafiają do kolejki i są wykonywane w miarę wolnych zasobów.
        Każdy test dostaje swój unikalny ID, cała partia dostaje jeden batchId
        którym można śledzić postęp przez endpoint /batches/{batchId}/stream.

        Limit: 1000 testów dziennie per klucz dostępu.
        """
)
```

Swagger UI dostępny pod `/swagger-ui.html`. Umożliwia badaczowi/promotorowi klikalne testowanie API bez pisania kodu.

### Bonus — auto-generowany klient Python

Dzięki OpenAPI można wygenerować klient Python jednym poleceniem:

```bash
openapi-python-client generate --url http://3.77.28.199/api/v1/openapi.yaml
```

Wtedy klient używa API tak:

```python
from symulatorsh_client import Client, models

client = Client(base_url="http://3.77.28.199", token="sk_live_...")
test = client.create_test(models.TestRequest(name="Mój test", duration_days=30, devices=[...]))
result = client.get_test_costs(test.test_id)
```

Argument dla pracy:
> „Dzięki pełnej specyfikacji OpenAPI możliwe jest automatyczne generowanie klientów w dowolnym języku programowania — Python, JavaScript, Go, Java."

---

## Obszar 6 — Ograniczanie zapytań i bezpieczeństwo

### Trzy limity

**1. Dzienny limit testów per klucz:** 1000 testów/dzień
- Reset o północy UTC.
- Dotyczy `POST /tests` i każdego testu w `POST /tests/batch` (partia 100 → 100 z 1000).

**2. Limit zapytań na sekundę per klucz:** 10 zapytań/sekundę
- Dotyczy wszystkich endpointów.
- Chroni przed hammeringiem GET-ów w pętli.

**3. Limit równoczesnych strumieni SSE per klucz:** 20 połączeń
- 21. próba dostaje HTTP 429.

### Implementacja liczników

**Cache Caffeine w pamięci JVM.** Prosty bean w Spring Boot, TTL na wpis. Wystarcza dla single-instance backendu.

**Argument dla pracy:**
> „Rate limiting zaimplementowany na poziomie aplikacji przez cache Caffeine z TTL. W środowisku produkcyjnym rekomenduje się migrację do zewnętrznego licznika (Redis) w celu zapewnienia trwałości między restartami."

### Odpowiedź 429 Too Many Requests

```json
HTTP 429 Too Many Requests
Retry-After: 3600
Content-Type: application/json

{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Przekroczono dzienny limit testów. Limit resetuje się o północy UTC.",
    "statusCode": 429,
    "details": {
      "limit": 1000,
      "used": 1000,
      "remaining": 0,
      "resetAt": "2026-08-16T00:00:00Z",
      "retryAfterSeconds": 3600
    }
  }
}
```

### Proaktywne nagłówki w każdej odpowiedzi

```
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 847
X-RateLimit-Reset: 1723852800
```

Klient Python:

```python
response = requests.post("/tests", ...)
remaining = int(response.headers['X-RateLimit-Remaining'])
if remaining < 10:
    print(f"Uwaga: zostało tylko {remaining} testów dzisiaj!")
```

To standard branżowy — GitHub API, Stripe, Twilio.

### Dodatkowe zabezpieczenia

**Hashowanie kluczy:** w bazie `SHA256(klucz)`, plain text tylko zwracamy raz przy rejestracji.

**CORS:** dozwolone origins: dev localhost, produkcyjny URL EC2, `*` dla API zewnętrznego (bezpieczne bo API i tak wymaga klucza).

**Max body size:** 10 MB per request.
```
spring.servlet.multipart.max-request-size=10MB
```

---

## Przykładowy skrypt Python — koniec-do-końca

Skrypt który zewnętrzny badacz może odpalić bez znajomości UI:

```python
import requests
import json
import time

BASE_URL = "http://3.77.28.199/api/v1"
API_KEY = "sk_live_a4f8c2b1d9e73f6a..."
HEADERS = {"X-API-Key": API_KEY}

# 1. Sprawdź swoje limity
me = requests.get(f"{BASE_URL}/auth/me", headers=HEADERS).json()
print(f"Zostało testów dzisiaj: {me['usage']['testsRemaining']}")

# 2. Pobierz listę dostępnych szablonów
templates = requests.get(f"{BASE_URL}/templates", headers=HEADERS).json()
print(f"Dostępne profile: {len(templates)}")

# 3. Uruchom partię 6 testów - wszystkie profile Zima
zima_templates = [t['id'] for t in templates if 'Zima' in t['name']]

batch_response = requests.post(
    f"{BASE_URL}/tests/batch",
    headers=HEADERS,
    json={
        "namePrefix": "API test 2026-08",
        "durationDays": 30,
        "speedFactor": 720,
        "emitEveryNMinutes": 15,
        "templateIds": zima_templates
    }
).json()

batch_id = batch_response['batchId']
print(f"Partia utworzona: {batch_id}, {batch_response['totalTests']} testów")

# 4. Śledź postęp partii na żywo
stream = requests.get(
    f"{BASE_URL}/batches/{batch_id}/stream",
    headers=HEADERS,
    stream=True
)

for line in stream.iter_lines():
    if line.startswith(b"data:"):
        event = json.loads(line[5:])
        print(f"[{event['completed']}/{event['total']}] "
              f"W trakcie: {len(event['currentlyRunning'])} testów")

        if event['completed'] + event['failed'] == event['total']:
            print("Partia ukończona!")
            break

# 5. Pobierz wyniki wszystkich testów
tests = requests.get(
    f"{BASE_URL}/tests?batchId={batch_id}",
    headers=HEADERS
).json()

wyniki = []
for test in tests:
    costs = requests.get(
        f"{BASE_URL}/tests/{test['testId']}/costs",
        headers=HEADERS
    ).json()
    wyniki.append({
        'nazwa': test['name'],
        'kwh': costs['energyKwh']['total'],
        'koszt_g11': costs['costs']['G11']['totalPln'],
        'koszt_rdn': costs['costs']['RDN']['totalPln'],
        'oszczednosc_pct': costs['savings']['rdnVsG11Percent'],
        'cvar_5': costs['risk']['rdnDailyCost']['cvar5Percent']
    })

# 6. Porównanie i eksport
import pandas as pd
df = pd.DataFrame(wyniki)
df.to_excel("moje_wyniki_zima.xlsx", index=False)
print(f"Zapisano {len(df)} wyników do pliku Excel.")
```

---

## Plan implementacji

### Faza 1 — Fundament (dzień 1)

- [ ] Migracja Flyway V4 — tabela `api_keys`
- [ ] Encja `ApiKey`, repository, service
- [ ] Endpoint `POST /api/v1/auth/register`
- [ ] Filter Spring Security walidujący nagłówek `X-API-Key`
- [ ] Hash SHA256 przy zapisie, porównanie przy walidacji

### Faza 2 — Rate limiting (dzień 2)

- [ ] Bean `RateLimitService` z Caffeine cache
- [ ] Middleware liczący requesty per klucz
- [ ] Nagłówki `X-RateLimit-*` w każdej odpowiedzi
- [ ] Endpoint `GET /api/v1/auth/me`
- [ ] Obsługa 429 Too Many Requests

### Faza 3 — Refaktor istniejących endpointów (dzień 3)

- [ ] Wszystkie kontrolery pod prefiks `/api/v1/`
- [ ] Dodać `X-API-Key` do wymaganych nagłówków
- [ ] Ujednolicony format błędu (`GlobalExceptionHandler`)
- [ ] HATEOAS links w response

### Faza 4 — Batch tracker (dzień 4)

- [ ] Kolumna `batch_id` w tabeli `tests` (migracja V5)
- [ ] Zwracany `batchId` w `POST /tests/batch`
- [ ] Endpoint `GET /api/v1/batches/{batchId}`
- [ ] Filtr `batchId` w `GET /api/v1/tests`
- [ ] Anulowanie całej partii (DELETE)

### Faza 5 — SSE (dzień 5)

- [ ] `SseEmitter` dla `GET /tests/{id}/stream`
- [ ] Emitowanie eventów progressu w `TestRunner` (co 25%)
- [ ] `SseEmitter` dla `GET /batches/{batchId}/stream`
- [ ] Keep-alive co 30 sekund
- [ ] Limit 20 równoczesnych połączeń per klucz

### Faza 6 — Eksport i porównanie (dzień 6)

- [ ] Endpoint `GET /tests/{id}/export?format=csv|xlsx`
- [ ] Generator CSV (Apache Commons)
- [ ] Generator XLSX (Apache POI, już mamy w skill xlsx)
- [ ] Endpoint `POST /tests/compare`
- [ ] Logika agregacji porównawczej

### Faza 7 — Dokumentacja (dzień 7)

- [ ] Adnotacje `@Operation` z polskimi opisami na wszystkich endpointach
- [ ] Adnotacje `@Schema` na wszystkich DTO
- [ ] Przykłady request/response w Swagger
- [ ] Przykładowy skrypt Python w README
- [ ] Adnotacje `@ApiResponse` dla kodów błędów

### Faza 8 — Testy manualne (dzień 8)

- [ ] Rejestracja klucza z Postman
- [ ] Uruchomienie testu przez Python
- [ ] Śledzenie SSE stream
- [ ] Eksport CSV
- [ ] Sprawdzenie rate limitów (429)
- [ ] Weryfikacja Swagger UI

**Suma:** ~8 dni pracy koncentrowanej. W realiach — 2 tygodnie z drobnymi refaktorami frontendu.

---

## Argumenty dla pracy magisterskiej

Do rozdziału **Implementacja**:

> „Platforma udostępnia publiczne REST API zaprojektowane zgodnie z zasadą **API-first**. Wszystkie funkcje dostępne przez interfejs graficzny mogą być również wywoływane programatycznie przez klienta HTTP. API obejmuje 19 endpointów podzielonych na sześć kategorii funkcjonalnych: autentykację, zarządzanie testami, obsługę partii, dostęp do wyników, zasoby pomocnicze oraz strumienie aktualizacji na żywo (SSE).
>
> API zabezpieczono przez system tokenów zgodny ze standardem RESTful — użytkownik uzyskuje token przez endpoint rejestracyjny i przekazuje go w nagłówku `X-API-Key`. Nałożono trzy poziomy ograniczeń: dzienny limit testów (1000), limit zapytań na sekundę (10) oraz limit równoczesnych strumieni SSE (20). W każdej odpowiedzi zwracane są proaktywne nagłówki `X-RateLimit-*` informujące klienta o pozostałym budżecie.
>
> Aktualizacje statusu długich operacji (symulacji) dostarczane są przez mechanizm Server-Sent Events zgodnie ze standardem W3C. Klient może subskrybować pojedynczy test lub całą partię jednym połączeniem HTTP — pozwala to na budowanie interaktywnych narzędzi analitycznych bez potrzeby pollingu.
>
> Kompletna specyfikacja OpenAPI 3.0 udostępniona jest pod `/swagger-ui.html`, co umożliwia automatyczne generowanie klientów w dowolnym języku programowania. Do pracy dołączono przykładowy skrypt Python demonstrujący pełny cykl życia badania — od rejestracji klucza przez uruchomienie partii testów po pobranie wyników i eksport do arkusza Excel."

Do rozdziału **Skalowalność / dalsze prace**:

> „Architektura API zaprojektowana została z myślą o skalowalności horyzontalnej. Backend jest bezstanowy w warstwie HTTP, testy przechowywane są w kolejce w PostgreSQL, workerzy symulacyjni mogą być dodawani/usuwani dynamicznie. Obecne ograniczenie do czterech równoczesnych symulacji wynika z bezpłatnego planu InfluxDB Cloud, nie z ograniczeń kodu. Przy migracji na płatny wariant infrastrukturalny (~500 USD/mies) system obsłużyłby około 100 równoczesnych symulacji na pojedynczej instancji."

---

## Zapamiętane decyzje

1. ✅ **Model auth:** prosty API key po rejestracji bez weryfikacji email
2. ✅ **Limit dzienny:** 1000 testów per klucz
3. ✅ **Limit rate:** 10 req/sec per klucz
4. ✅ **Limit SSE:** 20 równoczesnych strumieni per klucz
5. ✅ **Real-time:** Server-Sent Events (nie WebSocket, nie polling)
6. ✅ **Keep-alive:** 30 sekund
7. ✅ **Reconnect:** prosty (klient traci wcześniejsze eventy)
8. ✅ **Multi-stream:** jedno połączenie per test, ale jedno per cała partia
9. ✅ **Format URL/pola JSON:** angielski (REST konwencja)
10. ✅ **Format UI/dokumentacja/komunikaty:** polski (zgodnie z uwagą promotora)
11. ✅ **Wersjonowanie:** `/api/v1/` prefix
12. ✅ **Wersjonowanie:** brak planowanego v2
13. ✅ **Cache liczników:** Caffeine w pamięci (nie Redis)
14. ✅ **Hashowanie kluczy:** SHA256
15. ✅ **Format błędu:** jednolity, `error.code + message + details`
16. ✅ **Częściowy sukces batch:** tak (accepted + rejected)
17. ✅ **HATEOAS links:** tak, w response głównych endpointów
18. ✅ **Export format:** CSV + XLSX przez query parameter
19. ✅ **Pole tier na kluczu:** nie (wywalone jako overkill)

---

## Nierozstrzygnięte / do przemyślenia w trakcie implementacji

- [ ] Czy dodać webhook (POST na URL klienta) jako alternatywę SSE? — do decyzji w Fazie 5
- [ ] Retencja kluczy — po jakim czasie nieużywany klucz się dezaktywuje? — proponuję 90 dni
- [ ] Wersjonowanie eksportowanych CSV — czy dodać nagłówki z metadata testu? — do decyzji w Fazie 6
- [ ] Czy dodać `POST /auth/regenerate` — wygenerowanie nowego klucza dla użytkownika bez rejestracji od zera? — do przemyślenia

---

**Koniec dokumentu roboczego.** Do wykorzystania podczas implementacji API w tygodniu 3-10 sierpnia 2026.
