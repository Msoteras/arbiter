-- 2026-09-29 · Second step of 2026-09-29-quien-decidio-la-clasificacion.sql: drops the column
-- rules_classification replaced. Only after the new classification-, cases- and reports-service are
-- deployed and that migration was run once more: the old code still reads and writes this column,
-- and the re-run is what carries over the Fast Tracks it recorded in between.
--
-- Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        EXECUTE format('ALTER TABLE %I.cases DROP COLUMN IF EXISTS was_fast_track', tenant);
    END LOOP;
END $$;

COMMIT;
