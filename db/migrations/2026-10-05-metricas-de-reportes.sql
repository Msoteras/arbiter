-- 2026-10-05 · reports-service stores the figures of closed days, so a past period (the one a report
-- describes or the one it is compared against) is answered by adding days up instead of going back
-- to the cases. Four tables per tenant, owned by reports-service:
--   · metrics_day               a day whose figures are stored, even if nothing happened on it.
--   · metrics_daily_intake      claims filed on the day.
--   · metrics_daily_resolution  claims that reached a final status on the day, one row per status.
--   · metrics_daily_settlement  settlements authorized on the day.
-- Sums and counts only, never a rate or an average. They start empty: a day gets its rows the first
-- time a report asks for it.
-- Apply before deploying reports-service: it has no entity for these tables, so ddl-auto=validate
-- does not notice they are missing and the first report over a closed period fails instead.
-- Afterwards run the CREATE OR REPLACE FUNCTION arbiter_common.create_tenant_schema block from
-- init-multitenant.sql, or a new insurer is created without them. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant  TEXT;
    -- The claim cause catalog moves from arbiter_common to each tenant in
    -- 2026-10-05-ramos-por-aseguradora.sql; this works on either side of it.
    causes  TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        causes := CASE WHEN to_regclass(format('%I.claim_cause', tenant)) IS NOT NULL
                       THEN format('%I.claim_cause', tenant)
                       ELSE 'arbiter_common.claim_cause' END;

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I.metrics_day (
                day          DATE        PRIMARY KEY,
                computed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )', tenant);

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I.metrics_daily_intake (
                day             DATE   NOT NULL REFERENCES %I.metrics_day(day) ON DELETE CASCADE,
                claim_cause_id  BIGINT NOT NULL REFERENCES %s(id),
                analyst_id      BIGINT,
                reported        BIGINT NOT NULL,
                fast_track      BIGINT NOT NULL
            )', tenant, tenant, causes);

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I.metrics_daily_resolution (
                day                     DATE             NOT NULL REFERENCES %I.metrics_day(day) ON DELETE CASCADE,
                claim_cause_id          BIGINT           NOT NULL REFERENCES %s(id),
                analyst_id              BIGINT,
                final_status            VARCHAR(60)      NOT NULL,
                resolved                BIGINT           NOT NULL,
                total_seconds           DOUBLE PRECISION NOT NULL,
                waiting_seconds         DOUBLE PRECISION NOT NULL,
                fast_track              BIGINT           NOT NULL,
                fast_track_seconds      DOUBLE PRECISION NOT NULL,
                on_time                 BIGINT           NOT NULL,
                reopened                BIGINT           NOT NULL,
                agreement_eligible      BIGINT           NOT NULL,
                agreement_agreed        BIGINT           NOT NULL,
                fraud_determined        BIGINT           NOT NULL,
                fraud_backed_by_expert  BIGINT           NOT NULL,
                fraud_claimed_amount    NUMERIC(38,2)    NOT NULL
            )', tenant, tenant, causes);

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I.metrics_daily_settlement (
                day                  DATE          NOT NULL REFERENCES %I.metrics_day(day) ON DELETE CASCADE,
                claim_cause_id       BIGINT        NOT NULL REFERENCES %s(id),
                analyst_id           BIGINT,
                settlements          BIGINT        NOT NULL,
                settled_amount       NUMERIC(38,2) NOT NULL,
                claimed_amount       NUMERIC(38,2) NOT NULL,
                claimed_cases        BIGINT        NOT NULL,
                deductible_amount    NUMERIC(38,2) NOT NULL,
                installments_amount  NUMERIC(38,2) NOT NULL,
                overdue_amount       NUMERIC(38,2) NOT NULL
            )', tenant, tenant, causes);

        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_metrics_daily_intake_day ON %I.metrics_daily_intake (day)', tenant);
        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_metrics_daily_resolution_day ON %I.metrics_daily_resolution (day)', tenant);
        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_metrics_daily_settlement_day ON %I.metrics_daily_settlement (day)', tenant);

    END LOOP;
END $$;

COMMIT;
