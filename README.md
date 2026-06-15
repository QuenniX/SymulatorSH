# Smart Home Test Platform

Chmurowa platforma testowa do symulacji zużycia energii w gospodarstwie domowym. Inżynier konfiguruje scenariusz testu w formacie JSON (lista urządzeń, ich parametry, harmonogram zachowania, jitter), wysyła go do platformy przez REST API lub przez Web UI, platforma uruchamia symulację na osobnym wątku, a pomiary mocy chwilowej z każdego urządzenia trafiają do bazy time-series. Po zakończeniu testu dostępne są wykresy zużycia energii w czasie oraz agregaty (kWh per urządzenie, kWh całkowite).

Projekt powstaje w ramach pracy magisterskiej na Wydziale Elektrotechniki i Informatyki Politechniki Rzeszowskiej (autor: Igor Guła, EF-169784, promotor: dr inż. Michał Markiewicz).

## Struktura katalogów

```
PRACA_MAGISTERSKA/
├── backend/      # Spring Boot 3 (Java 21) - REST API, executor testów, klient MQTT
├── frontend/     # React + Vite + TypeScript + Tailwind - UI
├── infra/        # docker-compose, Caddyfile, mosquitto.conf
└── docs/         # architecture.md, json-schema.md, api-spec.md, roadmap.md
```

## Stack technologiczny

- **Backend:** Spring Boot 3.3, Java 21, Maven, PostgreSQL (Neon), InfluxDB Cloud, Eclipse Mosquitto (MQTT), Flyway, springdoc-openapi
- **Frontend:** React 18, Vite, TypeScript, Tailwind CSS, Recharts, Axios
- **Infrastruktura:** AWS EC2 (Ubuntu), Docker, Docker Compose, Caddy (HTTPS), GitHub Actions

## Szybki start (lokalnie)

```bash
# 1. Mosquitto + ewentualnie lokalny Postgres
docker compose -f infra/docker-compose-local.yml up -d

# 2. Backend
cd backend
# uzupełnij application-local.yml o Neon connection + InfluxDB credentials
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 3. Frontend
cd ../frontend
npm install
npm run dev
```

Backend nasłuchuje na `http://localhost:8080`, frontend na `http://localhost:5173`, Swagger UI dostępne pod `http://localhost:8080/swagger-ui.html`.

## Dokumenty

- [Architektura systemu](docs/architecture.md)
- [Schemat konfiguracji testu (JSON)](docs/json-schema.md)
- [Specyfikacja REST API](docs/api-spec.md)
- [Roadmap do września](docs/roadmap.md)
