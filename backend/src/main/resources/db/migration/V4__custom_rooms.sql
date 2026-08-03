
-- V4: Tabela wlasnych pomieszczen dodawanych przez uzytkownika przez kreator.
-- 5 domyslnych (KITCHEN, LIVING_ROOM, BEDROOM, BATHROOM, HALLWAY) pozostaje
-- hardkodowanych w RoomService jako "systemowe" - nie da sie ich usunac.
-- Tutaj lezą tylko wlasne pokoje typu "GARAZ", "TARAS" itd.


CREATE TABLE custom_rooms (
    type        VARCHAR(50)  PRIMARY KEY,     -- np. GARAZ, TARAS, PIWNICA (UPPER_CASE, bez spacji)
    label       VARCHAR(100) NOT NULL,        -- np. "Garaż", "Taras południowy"
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_custom_rooms_created ON custom_rooms (created_at DESC);

COMMENT ON TABLE custom_rooms IS 'Dodatkowe pomieszczenia definiowane przez uzytkownika (poza 5 systemowymi)';
COMMENT ON COLUMN custom_rooms.type IS 'Techniczny identyfikator UPPER_CASE bez polskich znakow, uzywany w JSON konfiguracji testu';
COMMENT ON COLUMN custom_rooms.label IS 'Nazwa wyswietlana z polskimi znakami';
