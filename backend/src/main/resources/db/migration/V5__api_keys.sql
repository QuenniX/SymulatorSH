
-- V5: Tabela kluczy API dla zewnetrznych uzytkownikow.
-- Klucz jest OPCJONALNY - endpointy publiczne, ale klucz podnosi limit
-- dziennego zuzycia (100 -> 1000 testow/dzien) i pozwala sledzic uzycie.


CREATE TABLE api_keys (
    id              UUID          PRIMARY KEY,
    -- SHA256 hex klucza. Plain text zwracamy TYLKO raz przy rejestracji.
    key_hash        VARCHAR(64)   NOT NULL UNIQUE,
    -- Metadata rejestracji (bez weryfikacji email)
    user_name       VARCHAR(255)  NOT NULL,
    email           VARCHAR(255)  NOT NULL,
    -- Limity - domyslnie 1000 test/dzien, mozna podniesc per klucz
    daily_limit     INTEGER       NOT NULL DEFAULT 1000,
    stream_limit    INTEGER       NOT NULL DEFAULT 20,
    -- Audyt
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_used_at    TIMESTAMPTZ,
    -- Blokada klucza (bez fizycznego DELETE zeby zachowac historie)
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_api_keys_hash    ON api_keys (key_hash);
CREATE INDEX idx_api_keys_email   ON api_keys (email);
CREATE INDEX idx_api_keys_created ON api_keys (created_at DESC);

COMMENT ON TABLE  api_keys IS 'Klucze API dla zewnetrznych uzytkownikow programatycznego dostepu';
COMMENT ON COLUMN api_keys.key_hash IS 'SHA256 klucza w hex. Klucz plain text nie jest przechowywany.';
COMMENT ON COLUMN api_keys.daily_limit IS 'Ile testow dziennie mozna uruchomic z tym kluczem (default 1000)';
COMMENT ON COLUMN api_keys.stream_limit IS 'Ile jednoczesnych polaczen SSE per klucz (default 20)';
COMMENT ON COLUMN api_keys.is_active IS 'False = klucz zablokowany, endpointy zwracaja 403 dla tego klucza';
