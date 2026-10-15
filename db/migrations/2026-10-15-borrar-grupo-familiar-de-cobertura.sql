-- 2026-10-15 · Drops coverage.covers_family_group. The engine now reads poliza.cubre_grupo_familiar
-- from the insurer database: the contract says it per policy, and it differs within a branch (8 of
-- BBVA's 12 phone policies reach the family, while all its phone coverages said no).
-- Idempotent. Apply after deploying the code: the entity no longer maps the column, and its
-- DEFAULT FALSE covers the inserts made in between.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = tenant AND table_name = 'coverage'
                   AND column_name = 'covers_family_group') THEN
            RAISE NOTICE '%: ya no tiene la columna', tenant;
            CONTINUE;
        END IF;

        EXECUTE format($sql$
            ALTER TABLE %1$I.coverage DROP COLUMN IF EXISTS covers_family_group;
        $sql$, tenant);

        RAISE NOTICE '%: columna borrada', tenant;
    END LOOP;
END $$;

COMMIT;
