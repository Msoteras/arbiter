-- 2026-10-22 · Adds insurer_logo to every tenant schema: the logo the referent uploads, shown in
-- the app bar. Purely additive. Idempotent. Apply BEFORE deploying the code: auth-service maps the
-- table and validates it on startup.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        EXECUTE format($sql$
            CREATE TABLE IF NOT EXISTS %1$I.insurer_logo (
                id              SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                content         BYTEA        NOT NULL,
                content_type    VARCHAR(50)  NOT NULL,
                updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
            );
        $sql$, tenant);

        RAISE NOTICE '%: insurer_logo lista', tenant;
    END LOOP;
END $$;

COMMIT;
