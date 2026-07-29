
-- V3: Tabela cen energii z Rynku Dnia Nastepnego (RDN)
-- Codzienny pobor z API PSE (raporty.pse.pl). 24 rekordy na dobe.
-- Uzywana do liczenia kosztu testow przy taryfie dynamicznej.


CREATE TABLE energy_prices (
    id              UUID PRIMARY KEY,
    market          VARCHAR(20)  NOT NULL,        -- na razie tylko 'RDN'
    delivery_date   DATE         NOT NULL,        -- doba dostawy energii
    hour            INTEGER      NOT NULL,        -- godzina dostawy 0-23
    price_pln_mwh   NUMERIC(10,2) NOT NULL,       -- cena w zl/MWh
    fetched_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE(market, delivery_date, hour)
);

CREATE INDEX idx_prices_date        ON energy_prices (delivery_date DESC);
CREATE INDEX idx_prices_market_date ON energy_prices (market, delivery_date);

COMMENT ON TABLE energy_prices IS 'Godzinowe ceny energii z RDN pobierane z API PSE';
COMMENT ON COLUMN energy_prices.market IS 'Nazwa rynku - obecnie tylko RDN';
COMMENT ON COLUMN energy_prices.delivery_date IS 'Data dostawy energii (nie pobrania)';
COMMENT ON COLUMN energy_prices.hour IS 'Godzina dostawy 0-23';
COMMENT ON COLUMN energy_prices.price_pln_mwh IS 'Cena rozliczeniowa w zl za MWh';
