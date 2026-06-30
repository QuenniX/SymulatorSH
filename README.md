
## Struktura katalogów

```
PRACA_MAGISTERSKA/
├── backend/      # Spring Boot 3 (Java 21) - REST API, executor testów, klient MQTT
├── frontend/     # React + Vite + TypeScript + Tailwind - UI
├── infra/        # docker-compose, Caddyfile, mosquitto.conf
└── docs/         # architecture.md, json-schema.md, api-spec.md
```

## Stack technologiczny

- **Backend:** Spring Boot 3.3, Java 21, Maven, PostgreSQL (Neon), InfluxDB Cloud, Eclipse Mosquitto (MQTT), Flyway, springdoc-openapi
- **Frontend:** React 18, Vite, TypeScript, Tailwind CSS, Recharts, Axios
- **Infrastruktura:** AWS EC2 (Ubuntu), Docker, Docker Compose, Caddy (HTTPS), GitHub Actions


## Dokumenty

- [Architektura systemu](docs/architecture.md)
- [Schemat konfiguracji testu (JSON)](docs/json-schema.md)
- [Specyfikacja REST API](docs/api-spec.md)

