-- 2026-09-01 · llm_analysis: whether the narrative matches the declared claim cause (cause_consistency,
-- suggested_claim_cause, cause_evidence). NULL means "not evaluated", never MATCHES. The cause is stored
-- by name, not as a foreign key: the analysis is immutable evidence. Apply before deploying. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format(
            $sql$ ALTER TABLE %I.llm_analysis
                    ADD COLUMN IF NOT EXISTS cause_consistency     VARCHAR(20),
                    ADD COLUMN IF NOT EXISTS suggested_claim_cause VARCHAR(120),
                    ADD COLUMN IF NOT EXISTS cause_evidence        TEXT $sql$, tenant);

        -- ADD CONSTRAINT has no IF NOT EXISTS: check first.
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
             WHERE conname = 'llm_analysis_cause_consistency_check'
               AND connamespace = tenant::regnamespace
        ) THEN
            EXECUTE format(
                $sql$ ALTER TABLE %I.llm_analysis
                        ADD CONSTRAINT llm_analysis_cause_consistency_check
                        CHECK (cause_consistency IS NULL
                               OR cause_consistency IN ('MATCHES', 'AMBIGUOUS', 'CONTRADICTS')) $sql$,
                tenant);
        END IF;

        RAISE NOTICE '%: llm_analysis lista', tenant;

    END LOOP;
END $$;

COMMIT;

-- Check: the three columns in every insurer schema.
SELECT table_schema, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'llm_analysis'
   AND column_name IN ('cause_consistency', 'suggested_claim_cause', 'cause_evidence')
 ORDER BY table_schema, column_name;
