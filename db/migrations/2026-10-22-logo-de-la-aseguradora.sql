-- 2026-10-22 · Adds arbiter_common.insurer_logo: the logo the referent uploads, shown in the app
-- bar. Also drops the per-tenant insurer_logo of an earlier draft of this change (empty, never
-- released). Idempotent. Apply BEFORE deploying the code: every module maps the common entities
-- and validates them on startup.

BEGIN;

CREATE TABLE IF NOT EXISTS arbiter_common.insurer_logo (
    insurer_id      BIGINT       PRIMARY KEY REFERENCES arbiter_common.insurer(id) ON DELETE CASCADE,
    content         BYTEA        NOT NULL,
    content_type    VARCHAR(50)  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        EXECUTE format('DROP TABLE IF EXISTS %I.insurer_logo', tenant);
    END LOOP;
END $$;

COMMIT;
