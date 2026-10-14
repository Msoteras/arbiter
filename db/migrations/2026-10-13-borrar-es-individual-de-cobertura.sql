-- 2026-10-13 · Drops coverage.is_individual. Nothing ever read it, and creating a coverage always
-- stored TRUE, even on one that reaches the family group: covers_family_group is the column the
-- referente edits and the engine evaluates.
-- Idempotent. Safe to apply after deploying the code: the entity no longer maps the column, and
-- its DEFAULT TRUE covers the inserts made in between.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = tenant AND table_name = 'coverage'
                   AND column_name = 'is_individual') THEN
            RAISE NOTICE '%: ya no tiene la columna', tenant;
            CONTINUE;
        END IF;

        EXECUTE format($sql$
            ALTER TABLE %1$I.coverage DROP COLUMN IF EXISTS is_individual;
        $sql$, tenant);

        RAISE NOTICE '%: columna borrada', tenant;
    END LOOP;
END $$;

COMMIT;
