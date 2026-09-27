-- 2026-09-22 · document_analysis.described_claim_cause: the cause each document narrates, so
-- ClaimCauseConsistencyEvaluator can compare it with the declared one in Fast Track (it warns, it doesn't
-- block). Nullable: NULL means "the document does not say", not a match. Idempotent.

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
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.tables
             WHERE table_schema = tenant AND table_name = 'document_analysis'
        ) THEN
            RAISE NOTICE 'Skipping %: no document_analysis table', tenant;
            CONTINUE;
        END IF;

        EXECUTE format(
            'ALTER TABLE %I.document_analysis ADD COLUMN IF NOT EXISTS described_claim_cause VARCHAR(120)',
            tenant);
        RAISE NOTICE '%: document_analysis.described_claim_cause OK', tenant;
    END LOOP;
END $$;

COMMIT;
