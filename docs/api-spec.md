# Specyfikacja REST API

Wszystkie endpointy są pod prefiksem `/api/v1/`. Format danych -- JSON. Kodowanie -- UTF-8. Każdy endpoint na potwierdzenie zwraca standardowe kody HTTP (200/201 sukces, 400 walidacja, 404 nieznany ID, 500 błąd serwera).

Dokumentacja interaktywna -- Swagger UI pod ścieżką `/swagger-ui.html` (generowane automatycznie przez springdoc-openapi z adnotacji w kodzie kontrolerów).

## Endpointy

### `GET /api/v1/device-types`

Zwraca listę dostępnych typów urządzeń wraz z ich domyślnymi parametrami. Wykorzystywane przez frontend do zbudowania palety.

**Odpowiedź 200:**

```json
[
  {
    "type": "LIGHT",
    "label": "Oświetlenie",
    "default_params": { "power_w": 60 }
  },
  {
    "type": "REFRIGERATOR",
    "label": "Lodówka",
    "default_params": { "power_w": 150, "duty_cycle": 0.4 }
  },
  {
    "type": "WASHER",
    "label": "Pralka",
    "default_params": { "power_w": 2000, "cycle_minutes": 60 }
  },
  {
    "type": "HEATER",
    "label": "Grzejnik elektryczny",
    "default_params": { "power_w": 1500 }
  }
]
```

---

### `POST /api/v1/tests`

Tworzy nowy test na podstawie JSON-a konfiguracji. Test zostaje wpisany do bazy ze statusem `QUEUED` i natychmiast wrzucony do executora -- zwrot z endpointu jest synchroniczny (200), ale sam test wykonuje się asynchronicznie w tle.

**Body:** zgodnie z [json-schema.md](json-schema.md).

**Odpowiedź 201:**

```json
{
  "test_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "status": "QUEUED",
  "created_at": "2026-06-15T14:32:10Z"
}
```

**Odpowiedź 400:**

```json
{
  "error": "VALIDATION_ERROR",
  "message": "speed_factor poza zakresem 1-1000",
  "field": "speed_factor"
}
```

---

### `GET /api/v1/tests`

Zwraca listę wszystkich testów (na MVP bez paginacji, posortowane od najnowszych).

**Parametry query (opcjonalne):**

- `status` -- filtruj po statusie (`QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`)
- `limit` -- ile rekordów (domyślnie 50, max 200)

**Odpowiedź 200:**

```json
[
  {
    "test_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "name": "Test dobowy - lodówka + oświetlenie kuchni",
    "status": "COMPLETED",
    "duration_days": 1,
    "speed_factor": 720,
    "created_at": "2026-06-15T14:32:10Z",
    "started_at": "2026-06-15T14:32:11Z",
    "finished_at": "2026-06-15T14:34:25Z"
  },
  {
    "test_id": "...",
    "name": "Test pralki nocą",
    "status": "RUNNING",
    "duration_days": 1,
    "speed_factor": 720,
    "created_at": "2026-06-15T14:50:00Z",
    "started_at": "2026-06-15T14:50:01Z",
    "finished_at": null
  }
]
```

---

### `GET /api/v1/tests/{id}`

Zwraca szczegóły konkretnego testu, w tym pełny JSON konfiguracji oraz agregaty (jeśli test zakończony).

**Odpowiedź 200:**

```json
{
  "test_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "name": "Test dobowy - lodówka + oświetlenie kuchni",
  "description": "Bazowy test pokazujący zużycie lodówki i pojedynczej żarówki kuchennej",
  "status": "COMPLETED",
  "config": { /* pełny JSON wysłany w POST /tests */ },
  "duration_days": 1,
  "speed_factor": 720,
  "created_at": "2026-06-15T14:32:10Z",
  "started_at": "2026-06-15T14:32:11Z",
  "finished_at": "2026-06-15T14:34:25Z",
  "real_duration_seconds": 134,
  "aggregates": {
    "total_kwh": 3.84,
    "per_device": [
      { "device_id": "fridge_01",     "kwh": 1.44 },
      { "device_id": "light_kitchen", "kwh": 0.27 },
      { "device_id": "washer_01",     "kwh": 2.00 }
    ]
  }
}
```

Gdy test jest w stanie `RUNNING`, pole `aggregates` może być `null` albo zawierać częściowe wartości (jeśli ich liczenie jest opłacalne na bieżąco).

---

### `GET /api/v1/tests/{id}/measurements`

Zwraca surowe pomiary z InfluxDB dla danego testu, w formacie tablicy punktów. Wykorzystywane przez frontend do narysowania wykresu.

**Parametry query (opcjonalne):**

- `device_id` -- ogranicz do jednego urządzenia
- `from` -- ISO-8601 timestamp (filtr początku)
- `to` -- ISO-8601 timestamp (filtr końca)
- `aggregate_minutes` -- agreguj do średnich w okienkach np. 5 minut (domyślnie surowe punkty)

**Odpowiedź 200:**

```json
{
  "test_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "points": [
    { "timestamp": "2026-06-15T14:32:11Z", "device_id": "fridge_01", "power_w": 150 },
    { "timestamp": "2026-06-15T14:32:11Z", "device_id": "light_kitchen", "power_w": 0 },
    { "timestamp": "2026-06-15T14:33:11Z", "device_id": "fridge_01", "power_w": 0 },
    { "timestamp": "2026-06-15T14:33:11Z", "device_id": "light_kitchen", "power_w": 0 }
  ]
}
```

---

### `DELETE /api/v1/tests/{id}`

Anuluje aktywny test (jeśli `RUNNING`, ustawia status `CANCELLED`) lub usuwa zakończony test wraz z jego pomiarami z InfluxDB.

**Odpowiedź 204:** (no content)

---

