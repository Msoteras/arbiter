-- 2026-09-10 · cases.documents_unverified_since: set at filing when rules-service could not answer, cleared
-- by DocumentRecheckScheduler once it checks the case. NULL is the normal case. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN
        SELECT schema_name
          FROM information_schema.schemata
         WHERE schema_name LIKE 'arbiter\_%'
           AND schema_name <> 'arbiter_common'
         ORDER BY schema_name
    LOOP
        -- A tenant schema without cases is half-created: skip it.
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.tables
             WHERE table_schema = tenant AND table_name = 'cases'
        ) THEN
            RAISE NOTICE 'Skipping %: no cases table', tenant;
            CONTINUE;
        END IF;

        EXECUTE format(
            'ALTER TABLE %I.cases ADD COLUMN IF NOT EXISTS documents_unverified_since TIMESTAMPTZ', tenant);

        RAISE NOTICE 'Migrated %', tenant;
    END LOOP;
END $$;

COMMIT;

-- ─── Verification ────────────────────────────────────────────────────────────
--
-- The column in every tenant schema (one row per tenant):
--
-- SELECT table_schema
--   FROM information_schema.columns
--  WHERE table_name = 'cases' AND column_name = 'documents_unverified_since'
--  ORDER BY table_schema;
