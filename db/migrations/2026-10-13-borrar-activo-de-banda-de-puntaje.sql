-- 2026-10-13 · Drops score_band.active. Bands are deleted and written again on every save of the
-- scoring configuration, always as TRUE, and no read ever filtered by it. Turning the scoring off
-- is scoring_configuration.active, which stays.
-- Refuses to run while a band is FALSE: dropping the column would bring it back into the scoring.
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
                 WHERE table_schema = tenant AND table_name = 'score_band'
                   AND column_name = 'active') THEN
            RAISE NOTICE '%: ya no tiene la columna', tenant;
            CONTINUE;
        END IF;

        EXECUTE format($sql$
            SELECT count(*) FROM %1$I.score_band WHERE NOT active
        $sql$, tenant) INTO inactive;

        IF inactive > 0 THEN
            RAISE EXCEPTION '%: % bandas de puntaje inactivas; borralas o activalas antes de seguir',
                tenant, inactive;
        END IF;

        EXECUTE format($sql$
            ALTER TABLE %1$I.score_band DROP COLUMN IF EXISTS active;
        $sql$, tenant);

        RAISE NOTICE '%: columna borrada', tenant;
    END LOOP;
END $$;

COMMIT;
