# Schemat konfiguracji testu (JSON)

Konfiguracja pojedynczego testu jest jednym obiektem JSON wysyłanym jako body żądania `POST /api/v1/tests`. Schemat jest celowo płaski i nieskomplikowany -- nie zakłada modelowania pomieszczeń, stref ani pokojów, bo platforma ma być **narzędziem inżyniera**, a nie produktem dla użytkownika końcowego. Inżynier wybiera dynamicznie urządzenia z palety i konfiguruje ich parametry.

## Struktura ogólna

```json
{
  "name": "string (wymagane)",
  "description": "string (opcjonalne)",
  "duration_days": 1,
  "speed_factor": 720,
  "retention_days": 30,
  "jitter": {
    "global_time_minutes": 15,
    "global_power_percent": 5
  },
  "devices": [
    { /* urządzenie 1 */ },
    { /* urządzenie 2 */ }
  ]
}
```

### Pola na poziomie testu

| Pole | Typ | Wymagane | Opis |
|------|-----|----------|------|
| `name` | string | tak | Krótka nazwa testu wyświetlana na liście |
| `description` | string | nie | Dłuższy opis (notatka inżyniera) |
| `duration_days` | int | tak | Długość symulacji w dniach (sym., nie real) |
| `speed_factor` | int | tak | Współczynnik przyspieszenia zegara symulacji; zakres 1--1000 |
| `retention_days` | int | nie (domyślnie 30) | Po ilu dniach pomiary mogą być automatycznie usunięte z InfluxDB |
| `jitter` | object | nie | Globalne odchylenia stosowane do wszystkich akcji urządzeń |
| `devices` | array | tak | Lista urządzeń wybranych do tego testu (min. 1) |

### Pole `jitter`

Jitter opisuje losowe odchylenia od harmonogramu, dzięki którym ten sam JSON nie produkuje identycznego dnia przy każdym uruchomieniu. Wartości domyślne stosowane są do wszystkich akcji wszystkich urządzeń, ale mogą być nadpisane indywidualnie w `schedule[].jitter_time_minutes`.

| Pole | Typ | Opis |
|------|-----|------|
| `global_time_minutes` | int | Domyślne odchylenie czasu rozpoczęcia akcji (rozkład jednostajny ± minut) |
| `global_power_percent` | int | Domyślne odchylenie chwilowej mocy (rozkład normalny, σ % nominalnej) |

## Schemat pojedynczego urządzenia

```json
{
  "id": "string (wymagane, unikalne w obrębie testu)",
  "type": "LIGHT | REFRIGERATOR | WASHER | HEATER | ...",
  "params": {
    "power_w": 60,
    "cycle_minutes": 60,
    "duty_cycle": 0.4
  },
  "schedule": [
    {
      "action": "ON",
      "at": "07:00",
      "duration_minutes": 30,
      "jitter_time_minutes": 10
    },
    {
      "action": "OFF",
      "at": "07:30"
    }
  ]
}
```

### Pola urządzenia

| Pole | Typ | Wymagane | Opis |
|------|-----|----------|------|
| `id` | string | tak | Unikalny identyfikator urządzenia w obrębie testu (np. `light_kitchen_01`) |
| `type` | enum | tak | Typ urządzenia z palety dostępnych typów |
| `params` | object | nie | Nadpisanie domyślnych parametrów typu (moc nominalna, długość cyklu, duty cycle, etc.) |
| `schedule` | array \| "always_on" \| "always_off" | tak | Lista akcji do wykonania w ciągu doby symulacji, albo wartość predefiniowana |

### Akcje w `schedule`

Każdy element tablicy `schedule` opisuje pojedynczą akcję sterującą wykonaną w określonym momencie doby.

| Pole | Typ | Wymagane | Opis |
|------|-----|----------|------|
| `action` | enum (ON / OFF / TOGGLE) | tak | Akcja do wykonania |
| `at` | string `HH:MM` | tak | Czas wykonania (w czasie symulacji, lokalnym) |
| `duration_minutes` | int | nie | Jeśli podane, po tym czasie automatycznie wykonywana jest akcja przeciwna |
| `jitter_time_minutes` | int | nie | Indywidualne odchylenie czasu (nadpisuje `global_time_minutes`) |

Wartości predefiniowane `"always_on"` i `"always_off"` to skrót dla urządzeń bez harmonogramu (np. lodówka).

## Pełny przykład

Test "Lodówka plus oświetlenie kuchni przez jeden dzień":

```json
{
  "name": "Test dobowy - lodówka + oświetlenie kuchni",
  "description": "Bazowy test pokazujący zużycie lodówki i pojedynczej żarówki kuchennej",
  "duration_days": 1,
  "speed_factor": 720,
  "retention_days": 30,
  "jitter": {
    "global_time_minutes": 15,
    "global_power_percent": 5
  },
  "devices": [
    {
      "id": "fridge_01",
      "type": "REFRIGERATOR",
      "params": {
        "power_w": 150,
        "duty_cycle": 0.4
      },
      "schedule": "always_on"
    },
    {
      "id": "light_kitchen",
      "type": "LIGHT",
      "params": {
        "power_w": 60
      },
      "schedule": [
        { "action": "ON",  "at": "07:00", "jitter_time_minutes": 15 },
        { "action": "OFF", "at": "07:30", "jitter_time_minutes": 10 },
        { "action": "ON",  "at": "19:00", "jitter_time_minutes": 20 },
        { "action": "OFF", "at": "23:00", "jitter_time_minutes": 15 }
      ]
    },
    {
      "id": "washer_01",
      "type": "WASHER",
      "params": {
        "power_w": 2000,
        "cycle_minutes": 60
      },
      "schedule": [
        {
          "action": "ON",
          "at": "22:30",
          "duration_minutes": 60,
          "jitter_time_minutes": 20
        }
      ]
    }
  ]
}
```

## Dostępne typy urządzeń (MVP)

| Typ | Domyślne `params` | Opis zachowania |
|-----|-------------------|------------------|
| `LIGHT` | `power_w: 60` | ON = pobiera `power_w`, OFF = 0 |
| `REFRIGERATOR` | `power_w: 150, duty_cycle: 0.4` | Cykle ON/OFF 15min/22min niezależnie od harmonogramu |
| `WASHER` | `power_w: 2000, cycle_minutes: 60` | Po komendzie ON cykl pracy `cycle_minutes` minut |
| `HEATER` | `power_w: 1500` | jak LIGHT, prosta para ON/OFF (na MVP) |

W kolejnych iteracjach -- `BATTERY` (akumulator z SOC), `SOLAR_PV` (panel słoneczny z modelem pogody), `OVEN`, `DRYER`, `AIR_CONDITIONER`.

## Walidacja

Backend waliduje:

- `name` niepuste,
- `duration_days >= 1`,
- `speed_factor` w zakresie 1--1000,
- `devices` niepuste i każde `id` unikalne,
- każdy `type` znany w palecie,
- każdy `at` w formacie `HH:MM` (24h),
- `action` w {`ON`, `OFF`, `TOGGLE`}.

Błąd walidacji zwraca HTTP 400 z opisem pola.
