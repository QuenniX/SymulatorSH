
-- V6: Kolumna batch_id w tabeli tests.
-- Grupuje testy uruchomione razem przez POST /api/v1/tests/batch.
-- NULLABLE - pojedyncze testy (POST /tests) nie maja batch_id.


ALTER TABLE tests ADD COLUMN batch_id UUID;

CREATE INDEX idx_tests_batch_id ON tests (batch_id) WHERE batch_id IS NOT NULL;

COMMENT ON COLUMN tests.batch_id IS
    'UUID partii testow uruchomionych razem przez POST /tests/batch. NULL dla testow pojedynczych.';
