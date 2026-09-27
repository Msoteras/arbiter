-- 2026-09-12 · expert_assessment.quoted_amount becomes repair_cost (the shop also bills a repair), and the
-- completeness check: required with QUOTE_SENT, optional with REPAIRED, forbidden with IRREPARABLE.
-- Run after 2026-09-12-presupuesto-del-taller.sql and before deploying the code. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        IF EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = tenant AND table_name = 'expert_assessment'
                      AND column_name = 'quoted_amount') THEN
            EXECUTE format(
                'ALTER TABLE %I.expert_assessment RENAME COLUMN quoted_amount TO repair_cost',
                tenant);
        END IF;

        EXECUTE format(
            'ALTER TABLE %I.expert_assessment
                ADD COLUMN IF NOT EXISTS repair_cost NUMERIC(15,2)', tenant);

        EXECUTE format(
            'ALTER TABLE %I.expert_assessment
                DROP CONSTRAINT IF EXISTS expert_assessment_report_complete', tenant);
        EXECUTE format(
            'ALTER TABLE %I.expert_assessment ADD CONSTRAINT expert_assessment_report_complete
                CHECK ((report_received_at IS NULL AND verdict IS NULL AND repair_outcome IS NULL
                        AND repair_cost IS NULL)
                       OR (report_received_at IS NOT NULL AND provider_type = ''ESTUDIO_LIQUIDADOR''
                           AND verdict IS NOT NULL AND repair_outcome IS NULL
                           AND repair_cost IS NULL)
                       OR (report_received_at IS NOT NULL AND provider_type = ''SERVICIO_TECNICO''
                           AND verdict IS NULL
                           AND ((repair_outcome = ''QUOTE_SENT'' AND repair_cost IS NOT NULL)
                                OR repair_outcome = ''REPAIRED''
                                OR (repair_outcome = ''IRREPARABLE'' AND repair_cost IS NULL))))',
            tenant);

    END LOOP;
END $$;

COMMIT;

-- Check: the renamed column, one row per insurer, and no quoted_amount left.
SELECT table_schema, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'expert_assessment' AND column_name IN ('repair_cost', 'quoted_amount')
 ORDER BY table_schema, column_name;
