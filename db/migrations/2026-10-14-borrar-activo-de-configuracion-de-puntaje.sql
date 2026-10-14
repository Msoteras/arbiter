-- 2026-10-14 · Drops scoring_configuration.active. The risk scoring is always on: the screen never
-- let the referente turn it off and always saved TRUE. The engine now scores whenever the insurer
-- has a usable configuration.
-- Refuses to run while a configuration is FALSE: dropping the column would turn its scoring on.
-- Idempotent. Safe to apply after deploying the code: the entity no longer maps the column, and
-- its DEFAULT TRUE covers the inserts made in between.

BEGIN;

DO $$
DECLARE
    tenant   TEXT;
    inactive BIGINT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = tenant AND table_name = 'scoring_configuration'
                   AND column_name = 'active') THEN
            RAISE NOTICE '%: ya no tiene la columna', tenant;
            CONTINUE;
        END IF;

        EXECUTE format($sql$
            SELECT count(*) FROM %1$I.scoring_configuration WHERE NOT active
        $sql$, tenant) INTO inactive;

        IF inactive > 0 THEN
            RAISE EXCEPTION '%: % configuraciones de puntaje apagadas; revisalas antes de seguir',
                tenant, inactive;
        END IF;

        EXECUTE format($sql$
            ALTER TABLE %1$I.scoring_configuration DROP COLUMN IF EXISTS active;
        $sql$, tenant);

        RAISE NOTICE '%: columna borrada', tenant;
    END LOOP;
END $$;

COMMIT;
