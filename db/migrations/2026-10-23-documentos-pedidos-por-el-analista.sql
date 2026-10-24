-- 2026-10-23 · Adds cases.requested_documents: every document the analyst asked the insured for,
-- outside the schedule, as [{type, description, reason, fulfilled}]. type is a catalog code, or
-- other_<n> for a document the catalog doesn't name. Who asked and when is on case_status_history.
-- Afterwards run the CREATE OR REPLACE FUNCTION arbiter_common.create_tenant_schema block from
-- init-multitenant.sql, or a new insurer is created without the column.
-- Idempotent. Apply BEFORE deploying the code: cases-service maps the column, and with
-- ddl-auto: validate it fails at startup without it. Older code ignores it, and the default covers
-- the inserts it makes in between.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        EXECUTE format($sql$
            ALTER TABLE %1$I.cases
                ADD COLUMN IF NOT EXISTS requested_documents JSONB NOT NULL DEFAULT '[]';
        $sql$, tenant);

        RAISE NOTICE '%: columna lista', tenant;
    END LOOP;
END $$;

COMMIT;
