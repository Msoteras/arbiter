-- 2026-10-18 · Drops cases.destination. Nothing ever wrote or read it, and its values were never
-- defined: where a case goes is already told by its status, its classification and its referrals.
-- Idempotent. Safe to apply after deploying the code: the entity no longer maps the column, and it
-- is nullable, so the inserts made in between leave it NULL.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = tenant AND table_name = 'cases'
                   AND column_name = 'destination') THEN
            RAISE NOTICE '%: ya no tiene la columna', tenant;
            CONTINUE;
        END IF;

        EXECUTE format($sql$
            ALTER TABLE %1$I.cases DROP COLUMN IF EXISTS destination;
        $sql$, tenant);

        RAISE NOTICE '%: columna borrada', tenant;
    END LOOP;
END $$;

COMMIT;
