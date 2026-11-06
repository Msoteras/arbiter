-- 2026-11-03 · reports-service keeps its daily figures in one narrow table, one row per metric:
--   · metrics_daily_value  replaces metrics_daily_intake, metrics_daily_resolution and
--                          metrics_daily_settlement. Adding a metric no longer needs DDL.
--   · metrics_day          gains definitions_version: a day stored with an older version is stored
--                          again the next time a report asks for it.
-- metrics_day is emptied: every closed day is rebuilt from the cases the first time a report asks
-- for it, so nothing is migrated. Note the frozen figures of a day go with it: a day stored before
-- a later correction to its cases comes back with the corrected numbers.
-- Apply together with the reports-service deploy: the old code fails on the new tables and the new
-- code on the old ones. Afterwards run the CREATE OR REPLACE FUNCTION
-- arbiter_common.create_tenant_schema block from init-multitenant.sql, or a new insurer is created
-- with the old tables. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant  TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format('DROP TABLE IF EXISTS %I.metrics_daily_intake', tenant);
        EXECUTE format('DROP TABLE IF EXISTS %I.metrics_daily_resolution', tenant);
        EXECUTE format('DROP TABLE IF EXISTS %I.metrics_daily_settlement', tenant);

        EXECUTE format('DELETE FROM %I.metrics_day', tenant);
        EXECUTE format(
            'ALTER TABLE %I.metrics_day ADD COLUMN IF NOT EXISTS definitions_version INTEGER NOT NULL',
            tenant);

        EXECUTE format($ddl$
            CREATE TABLE IF NOT EXISTS %I.metrics_daily_value (
                day             DATE          NOT NULL REFERENCES %I.metrics_day(day) ON DELETE CASCADE,
                metric          VARCHAR(60)   NOT NULL,
                claim_cause_id  BIGINT        NOT NULL REFERENCES %I.claim_cause(id),
                analyst_id      BIGINT,
                segment         VARCHAR(60)   NOT NULL,
                value           NUMERIC(38,4) NOT NULL
            )$ddl$, tenant, tenant, tenant);

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_metrics_daily_value_day_metric ON %I.metrics_daily_value (day, metric)',
            tenant);

        RAISE NOTICE 'Esquema %: métricas diarias en metrics_daily_value. Los días se vuelven a calcular cuando un reporte los pida.', tenant;

    END LOOP;
END $$;

COMMIT;
