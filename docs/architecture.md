# Architektura systemu

## Przegląd

Platforma jest aplikacją chmurową typu SaaS, w której **operator** (inżynier zadający testy) konfiguruje scenariusz symulacji w formacie JSON i zleca jego wykonanie przez REST API lub Web UI. Sam silnik symulacji uruchamiany jest po stronie serwerowej w tle, pomiary mocy chwilowej z poszczególnych urządzeń trafiają do bazy time-series, a wyniki dostępne są w postaci wykresów oraz agregatów (kWh per urządzenie, kWh całkowite).

Architektura opiera się na dwóch równoległych kanałach komunikacji. **REST API** służy do zarządzania testami (utworzenie, status, pobranie wyników) i jest wykorzystywane zarówno przez frontend, jak i przez ewentualne zewnętrzne skrypty. **MQTT** jest wewnętrznym kanałem strumieniowania pomiarów -- symulator urządzeń publikuje na nim swój stan i moc chwilową, backend nasłuchuje i zapisuje punkty do bazy InfluxDB.

## Komponenty

```
                   ┌──────────────────┐
                   │   Frontend (UI)  │
                   │  React + Vite    │
                   └────────┬─────────┘
                            │ HTTPS (REST + polling)
                            ▼
   ┌─────────────────────────────────────────────────┐
   │              Backend Spring Boot                │
   │  • REST API (POST/GET tests, device-types)      │
   │  • TestExecutor (wątek tła - symulacja)         │
   │  • Klient MQTT (publish + subscribe)            │
   │  • Klient InfluxDB (zapis pomiarów)             │
   └───────┬───────────────────┬─────────────┬───────┘
           │                   │             │
           │ JDBC              │ MQTT        │ HTTP
           ▼                   ▼             ▼
   ┌──────────────┐   ┌────────────────┐  ┌──────────────────┐
   │ PostgreSQL   │   │   Mosquitto    │  │  InfluxDB Cloud  │
   │   (Neon)     │   │   (broker)     │  │ (time-series DB) │
   │              │   │                │  │                  │
   │ • tests      │   │  Wewnętrzne    │  │ • measurements   │
   │ • device     │   │  topiki:       │  │   (power_w)      │
   │   metadata   │   │  tests/{id}/   │  │                  │
   └──────────────┘   │  devices/+/    │  └──────────────────┘
                      │  energy        │
                      └────────────────┘
```

## Przepływ danych przy uruchomieniu testu

1. Operator wysyła `POST /api/v1/tests` z JSON-em konfiguracji (lista urządzeń, ich parametry, harmonogram, jitter, `duration_days`, `speed_factor`).
2. Backend waliduje JSON, zapisuje rekord do tabeli `tests` w PostgreSQL ze statusem `QUEUED`, zwraca `{test_id, status}`.
3. `TestExecutor` (singleton z `ExecutorService`) odbiera zadanie, ustawia status na `RUNNING`, parsuje konfigurację i tworzy instancje klas symulatorów dla każdego z urządzeń (`LightSimulator`, `RefrigeratorSimulator`, `WasherSimulator`, ...).
4. W głównej pętli wykonawczej (krok symulowany skalowany przez `speed_factor`) executor wywołuje metodę `update(currentTime)` na każdym symulatorze, otrzymuje bieżącą moc i publikuje wiadomość MQTT na temacie `tests/{test_id}/devices/{device_id}/energy` w formacie `{timestamp, power_w}`.
5. `MqttMessageHandler` w tym samym backendzie odbiera wiadomość i zapisuje punkt do InfluxDB z tagami `test_id` i `device_id`.
6. Po osiągnięciu `duration_days` (w czasie symulowanym) executor ustawia status testu na `COMPLETED`, zapisuje `finished_at`.
7. Frontend w trakcie testu odpytuje endpoint `GET /api/v1/tests/{id}` co kilka sekund (polling) i buduje na żywo wykres z pomiarów dostępnych w `GET /api/v1/tests/{id}/measurements`.

## Dwa kanały dostępu

Platforma celowo wystawia jeden i ten sam silnik dwoma kanałami:

- **Programatyczny** -- zewnętrzny skrypt strzela curl-em w `POST /api/v1/tests`, dostaje `test_id`, pollluje status, pobiera wyniki. Wykorzystywany do automatyzacji, batch runs, regresji.
- **Klikalny** -- operator otwiera UI w przeglądarce, wybiera urządzenia z palety, ustawia parametry, klika "Utwórz test". Pod spodem frontend wykonuje dokładnie te same żądania REST.

Z perspektywy backendu oba kanały są nieodróżnialne -- to ten sam endpoint, ta sama kolejka, ten sam executor.

## Decyzje technologiczne

### Dlaczego dwie bazy danych?

**PostgreSQL** trzyma **strukturę systemu** -- metadane testów (id, nazwa, status, JSON konfiguracji, znaczniki czasowe), w przyszłości użytkowników i audit log. Klasyczna baza relacyjna z transakcjami, joinami i Flyway migracjami.

**InfluxDB** trzyma **strumień pomiarów** -- czas, urządzenie, moc chwilowa. Wyspecjalizowana baza time-series z natywnym operatorem `integral(unit: 1h)` do całkowania mocy w energię (Wh), polityką retencji i wydajnym append-only. Pojedynczy 7-dniowy test generuje rzędu miliona punktów -- Postgres byłby zbyt wolny przy zapisach i agregacjach po czasie.

To klasyczny wzorzec **polyglot persistence**: każda baza robi to, w czym jest najlepsza.

### Dlaczego MQTT?

MQTT jest standardem branżowym w obszarze Internetu Rzeczy. Architektura publish-subscribe odseparowuje publisherów (symulatory urządzeń) od subskrybentów (backend zapisujący do InfluxDB), co umożliwi w przyszłości:

- wpinanie zewnętrznych klientów sterujących (algorytmów decyzyjnych, kontrolerów PLC) jako kolejnych subscriberów,
- przeniesienie symulatora do osobnego procesu/kontenera bez zmian w API,
- podpinanie realnych urządzeń z modułami MQTT.

Na MVP broker (Eclipse Mosquitto) działa lokalnie w sieci Docker -- bez TLS i autoryzacji. W kolejnych iteracjach przejście na AWS IoT Core lub HiveMQ Cloud z certyfikatami klienckimi i ACL per temat.

### Dlaczego pojedynczy proces na MVP?

Backend, executor, symulator urządzeń i odbiornik MQTT siedzą na MVP w jednej aplikacji Spring Boot. To upraszcza deployment (jeden kontener), debugowanie (jeden log) i konfigurację (jeden `application.yml`). Architektonicznie executor jest izolowany jako `@Service` z własnym pulem wątków, dzięki czemu w drugiej iteracji można wyciągnąć go do osobnego mikroserwisu z kolejką (RabbitMQ albo Postgres `LISTEN/NOTIFY`) bez przerabiania pozostałych warstw.

## Środowiska

| Środowisko | Backend | Frontend | Postgres | InfluxDB | MQTT |
|------------|---------|----------|----------|----------|------|
| **lokalne** | IntelliJ, Run | `npm run dev` | Neon (cloud) lub Docker | InfluxDB Cloud | Mosquitto w Dockerze |
| **produkcyjne** | Docker na EC2 | Docker na EC2 | Neon (cloud) | InfluxDB Cloud | Mosquitto na EC2 |

Bazy danych są wspólne dla obu środowisk (lub w pełni rozdzielne -- decyzja per developer). W produkcji wszystko stoi za reverse proxy **Caddy**, który obsługuje HTTPS przez automatycznie pobierane certyfikaty Let's Encrypt.
