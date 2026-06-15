# Roadmap do września

Cel: do złożenia pracy magisterskiej (wrzesień 2026) zbudować w pełni działającą platformę testową w chmurze AWS, opisać ją w pracy i obronić. Trzy miesiące, podział na trzy etapy: czerwiec (MVP), lipiec (UX i funkcje), sierpień (dopracowanie + pisanie).

## Etap 1 -- czerwiec: MVP w chmurze

Cel: pierwsza wersja działająca pod publicznym adresem, demonstrowalna promotorowi.

**Infrastruktura**

- [ ] Repo na GitHubie (private, invite dla promotora)
- [ ] Konto AWS, EC2 t3.small (Ubuntu) z Docker, Elastic IP, security group
- [ ] Domena + DNS, Caddy z auto-SSL Let's Encrypt
- [ ] Konta Neon (Postgres) i InfluxDB Cloud (time-series)
- [ ] GitHub Actions -- build obrazów, push do GHCR

**Backend (Spring Boot 3.3 / Java 21)**

- [ ] Szkielet projektu, profile `local`/`prod`
- [ ] Flyway V1: tabela `tests`
- [ ] Endpointy: `POST /tests`, `GET /tests`, `GET /tests/{id}`, `GET /device-types`, `GET /tests/{id}/measurements`, `DELETE /tests/{id}`
- [ ] Walidacja JSON konfiguracji
- [ ] `TestExecutor` z `ExecutorService` (3 wątki)
- [ ] Trzy klasy symulatorów: `LightSimulator`, `RefrigeratorSimulator`, `WasherSimulator`
- [ ] Klient MQTT (paho) -- publish + subscribe
- [ ] Klient InfluxDB -- zapis pomiarów
- [ ] springdoc-openapi -- Swagger UI

**Frontend (React + Vite + TypeScript)**

- [ ] Szkielet projektu, Tailwind, Axios
- [ ] Strona listy testów (z `GET /tests`)
- [ ] Strona nowego testu (textarea z JSON, walidacja podstawowa)
- [ ] Strona szczegółów testu z wykresem (Recharts, polling co 3s)
- [ ] Layout, nawigacja

**Deployment + smoke test**

- [ ] Dockerfile backend (multistage)
- [ ] Dockerfile frontend (multistage + nginx static)
- [ ] `docker-compose.yml` prod (backend, frontend, mosquitto, caddy)
- [ ] Caddyfile (HTTPS, reverse proxy)
- [ ] Deploy na EC2, smoke test pod publicznym adresem
- [ ] Demo dla promotora

## Etap 2 -- lipiec: UX i funkcje

Cel: platforma używalna jak narzędzie, nie tylko jak demo.

- [ ] **Paleta urządzeń w UI** -- zamiast textarei z JSON, drag&drop lub formularz wybierający z palety
- [ ] **Walidacja JSON na frontendzie** z podświetleniem błędów
- [ ] **SSE (Server-Sent Events)** -- live update statusu testu i pomiarów zamiast pollingu
- [ ] **Tryb interaktywny** -- `mode: INTERACTIVE` -- urządzenia stoją, operator klika w UI ON/OFF, pomiary lecą do bazy, kończy się manualnie
- [ ] **Anulowanie testów** w trakcie wykonywania
- [ ] **Dodatkowe typy urządzeń**: `BATTERY` (akumulator z SOC), `SOLAR_PV` (panel z modelem pogody), `OVEN`, `DRYER`, `AIR_CONDITIONER`
- [ ] **Agregaty w wynikach** -- kWh per urządzenie, kWh całkowite, średnia moc, profile godzinowe
- [ ] **Lepsze wykresy** -- per urządzenie, stacked area, podział dzień/noc
- [ ] **Filtrowanie i sortowanie listy testów** w UI
- [ ] **Eksport wyników do CSV** -- na życzenie promotora
- [ ] **AWS IoT Core** zamiast lokalnego Mosquitto -- managed broker MQTT
- [ ] **Persistencja konfiguracji** -- biblioteka zapisanych szablonów testów (do reużywania)

## Etap 3 -- sierpień: dopracowanie + pisanie

Cel: rzeczy które są opcjonalne dla działania, ale podnoszą pracę magisterską i robią dobre wrażenie.

- [ ] **Wersjonowanie konfiguracji JSON** (`config_version: "1.0"`)
- [ ] **API key authentication** -- jeden klucz zarządzający dla operatora
- [ ] **Testy obciążeniowe** -- ile równoległych testów uniesie t3.small (sekcja "wyniki" w pracy)
- [ ] **Observability** -- Prometheus metrics, Grafana dashboard z metrykami platformy (liczba testów, czas wykonania, throughput MQTT)
- [ ] **Audit log** -- każda komenda i każdy test w osobnej tabeli
- [ ] **Dokumentacja API** -- Swagger spec wygenerowany + opis w pracy
- [ ] **Pisanie pracy magisterskiej** -- 8 rozdziałów teorii + część projektowa
- [ ] **Diagramy architektury** w TikZ do pracy
- [ ] **Slajdy obrony** -- ~15 slajdów

## Co celowo zostawiamy poza zakresem MVP

- **Machine Learning** -- promotor zgodził się, że ML wraca dopiero po stworzeniu solidnej podstawy. Strategia~C (klasyfikator obecności + prognoza zużycia) była przedmiotem dotychczasowej iteracji, w nowej platformie ML stanie się kolejnym typem klienta API.
- **Multi-tenancy** -- na razie jeden operator (hardkodowany `owner_id`). Po ML.
- **Realne urządzenia** -- wyłącznie symulatory. Wpięcie realnego Raspberry Pi z czujnikami byłoby naturalnym rozszerzeniem, ale wykracza poza zakres pracy.
- **Mobilna aplikacja** -- tylko web UI.
- **Plany taryfowe i koszty PLN** -- na MVP same kWh, ewentualnie w sierpniu jako rozszerzenie.

## Kamienie milowe / spotkania z promotorem

| Termin | Co pokazujemy |
|--------|---------------|
| Koniec czerwca | MVP w chmurze działający, demo "wklejam JSON → widzę wykres" |
| Środek lipca | UI z paletą urządzeń, SSE, tryb interaktywny, BATTERY + SOLAR_PV |
| Koniec lipca | Pełen zestaw typów urządzeń, eksport CSV, testy obciążeniowe |
| Środek sierpnia | Draft pracy magisterskiej, pierwsze 30 stron |
| Koniec sierpnia | Cała praca napisana, dostosowanie pod uwagi promotora |
| Wrzesień | Obrona |
