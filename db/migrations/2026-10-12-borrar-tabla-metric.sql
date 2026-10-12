-- 2026-10-12 · Drops the tenant table `metric`. init-multitenant.sql stopped creating it on
-- 2026-09-12 (it was never read or written; reports-service keeps its totals in metrics_day and the
-- metrics_daily_* tables), but the insurers created before that still had it.
-- Refuses to drop a table with rows, and uses no CASCADE: anything depending on it must fail here.
-- Idempotent.

BEGIN;

DO $$
DECLARE
    tenant  TEXT;
    stored  BIGINT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        IF to_regclass(format('%I.metric', tenant)) IS NULL THEN
            RAISE NOTICE '%: no tiene la tabla', tenant;
            CONTINUE;
        END IF;

        EXECUTE format('SELECT count(*) FROM %I.metric', tenant) INTO stored;
        IF stored > 0 THEN
            RAISE EXCEPTION '%: metric tiene % filas; revisalas antes de borrarla', tenant, stored;
        END IF;

        EXECUTE format('DROP TABLE %I.metric', tenant);
        RAISE NOTICE '%: tabla borrada', tenant;
    END LOOP;
END $$;

COMMIT;
