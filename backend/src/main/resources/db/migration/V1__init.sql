-- =========================================================
-- V1: Inicjalna struktura bazy danych
-- =========================================================
-- Tabela tests trzyma metadane wszystkich uruchomionych testów.
-- Pomiary mocy chwilowej żyją osobno - w InfluxDB Cloud.
-- =========================================================

CREATE TABLE tests (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    config_json     JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    duration_days   INTEGER      NOT NULL,
    speed_factor    INTEGER      NOT NULL,
    owner_id        VARCHAR(64)  NOT NULL DEFAULT 'default',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    error_message   TEXT
);

CREATE INDEX idx_tests_status      ON tests (status);
CREATE INDEX idx_tests_owner       ON tests (owner_id);
CREATE INDEX idx_tests_created_at  ON tests (created_at DESC);

COMMENT ON TABLE tests IS 'Metadane testów symulacyjnych';
COMMENT ON COLUMN tests.status IS 'QUEUED | RUNNING | COMPLETED | FAILED | CANCELLED';
COMMENT ON COLUMN tests.config_json IS 'Pełny JSON konfiguracji wysłany w POST /tests';
COMMENT ON COLUMN tests.duration_days IS 'Długość symulowanej doby w dniach';
COMMENT ON COLUMN tests.speed_factor IS 'Współczynnik przyspieszenia symulacji';
