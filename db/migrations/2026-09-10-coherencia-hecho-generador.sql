-- 2026-09-10 · llm_analysis: whether the narrative matches the declared claim cause (cause_consistency,
-- suggested_claim_cause, cause_evidence). Added by hand on the deployed database before; idempotent.

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
        -- A tenant schema without llm_analysis is half-created: skip it.
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.tables
             WHERE table_schema = tenant AND table_name = 'llm_analysis'
        ) THEN
            RAISE NOTICE 'Skipping %: no llm_analysis table', tenant;
            CONTINUE;
        END IF;

        EXECUTE format(
            'ALTER TABLE %I.llm_analysis ADD COLUMN IF NOT EXISTS cause_consistency VARCHAR(20)', tenant);
        EXECUTE format(
            'ALTER TABLE %I.llm_analysis ADD COLUMN IF NOT EXISTS suggested_claim_cause VARCHAR(120)', tenant);
        EXECUTE format(
            'ALTER TABLE %I.llm_analysis ADD COLUMN IF NOT EXISTS cause_evidence TEXT', tenant);

        -- ADD CONSTRAINT has no IF NOT EXISTS: check first, so a second run is a no-op.
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conname = 'llm_analysis_cause_consistency_check'
               AND conrelid = format('%I.llm_analysis', tenant)::regclass
        ) THEN
            EXECUTE format($ddl$
                ALTER TABLE %I.llm_analysis
                    ADD CONSTRAINT llm_analysis_cause_consistency_check CHECK (
                        cause_consistency IS NULL
                        OR cause_consistency IN ('MATCHES', 'AMBIGUOUS', 'CONTRADICTS')
                    )$ddl$, tenant);
        END IF;

        RAISE NOTICE 'Migrated %', tenant;
    END LOOP;
END $$;

COMMIT;

-- ─── Verification ────────────────────────────────────────────────────────────
--
-- 1. The three columns in every tenant schema (one row per tenant):
--
-- SELECT table_schema, string_agg(column_name, ', ' ORDER BY column_name)
--   FROM information_schema.columns
--  WHERE table_name = 'llm_analysis'
--    AND column_name IN ('cause_consistency', 'suggested_claim_cause', 'cause_evidence')
--  GROUP BY table_schema
--  ORDER BY table_schema;
--
-- 2. The CHECK in every tenant schema:
--
-- SELECT conrelid::regclass, pg_get_constraintdef(oid)
--   FROM pg_constraint
--  WHERE conname = 'llm_analysis_cause_consistency_check';
