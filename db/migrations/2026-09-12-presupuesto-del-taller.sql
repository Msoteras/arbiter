-- 2026-09-12 · expert_assessment.quoted_amount, the repair shop's price. Apart from indemnifiable_amount:
-- the expert says what the claim is worth, the shop what fixing it costs. Apply before deploying. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format(
            'ALTER TABLE %I.expert_assessment
                ADD COLUMN IF NOT EXISTS quoted_amount NUMERIC(15,2)', tenant);

        EXECUTE format(
            'ALTER TABLE %I.expert_assessment
                DROP CONSTRAINT IF EXISTS expert_assessment_report_complete', tenant);
        EXECUTE format(
            'ALTER TABLE %I.expert_assessment ADD CONSTRAINT expert_assessment_report_complete
                CHECK ((report_received_at IS NULL AND verdict IS NULL AND repair_outcome IS NULL
                        AND quoted_amount IS NULL)
                       OR (report_received_at IS NOT NULL AND provider_type = ''ESTUDIO_LIQUIDADOR''
                           AND verdict IS NOT NULL AND repair_outcome IS NULL
                           AND quoted_amount IS NULL)
                       OR (report_received_at IS NOT NULL AND provider_type = ''SERVICIO_TECNICO''
                           AND repair_outcome IS NOT NULL AND verdict IS NULL
                           AND (quoted_amount IS NOT NULL) = (repair_outcome = ''QUOTE_SENT'')))',
            tenant);

    END LOOP;
END $$;

COMMIT;

-- Check: the new column, one row per insurer.
SELECT table_schema, table_name, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE column_name = 'quoted_amount' AND table_name = 'expert_assessment'
 ORDER BY table_schema;
