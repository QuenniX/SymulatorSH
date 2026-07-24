
-- V2: Tabela szablonów konfiguracji testów
-- Pozwala zapisywać i wczytywać gotowe konfiguracje w kreatorze.


CREATE TABLE templates (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    config_json     JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_templates_name       ON templates (name);
CREATE INDEX idx_templates_created_at ON templates (created_at DESC);

COMMENT ON TABLE templates IS 'Zapisane szablony konfiguracji testów';
COMMENT ON COLUMN templates.config_json IS 'JSON konfiguracji testu do wczytania w kreatorze';
