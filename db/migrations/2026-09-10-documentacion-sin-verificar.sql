-- =============================================================================
-- cases.documents_unverified_since — denuncias filed without their document
-- schedule verified
--
-- Since 02/09 cases-service demands the document schedule at filing
-- (CaseServiceImpl.verifyRequiredDocuments), and reading it depends on
-- rules-service. When rules-service doesn't answer the denuncia still goes in —
-- leaving the insured out over an outage of ours would be worse — but until now
-- nothing recorded that, and nothing came back to check it. This column is the
-- mark: set at filing when the schedule was unreadable, cleared by
-- DocumentRecheckScheduler once it checks the case. NULL is the normal case.
--
-- Idempotent and additive. Existing cases stay NULL: one filed during an outage
-- before this migration can't be told apart from a verified one.
--
-- Usage:
--   psql "$DATABASE_URL" -f db/migrations/2026-09-10-documentacion-sin-verificar.sql
-- =============================================================================

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
        -- A tenant schema without cases is a half-created one; skip it instead of failing the
        -- whole migration over it.
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
