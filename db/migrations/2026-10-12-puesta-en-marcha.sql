-- 2026-10-12 · The referent's setup checklist ("Puesta en marcha"). Two tables per tenant, owned by
-- rules-service:
--   · onboarding_step   steps marked as not used, and the last save of each step.
--   · onboarding_reset  when a referent restarted the checklist (a single row).
-- They start empty: with no rows, every step's state comes from the configuration itself.
-- Apply before deploying rules-service: with ddl-auto=validate it does not start without them.
-- Afterwards run the CREATE OR REPLACE FUNCTION arbiter_common.create_tenant_schema block from
-- init-multitenant.sql, or a new insurer is created without them. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant  TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I.onboarding_step (
                step_key    VARCHAR(40) PRIMARY KEY,
                skipped     BOOLEAN     NOT NULL DEFAULT FALSE,
                touched_at  TIMESTAMPTZ
            )', tenant);

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I.onboarding_reset (
                id        SMALLINT    PRIMARY KEY DEFAULT 1,
                reset_at  TIMESTAMPTZ NOT NULL,
                reset_by  BIGINT      REFERENCES arbiter_common.users(id),

                CONSTRAINT onboarding_reset_single_row CHECK (id = 1)
            )', tenant);

        RAISE NOTICE 'Puesta en marcha: tablas listas en %', tenant;
    END LOOP;
END $$;

COMMIT;
