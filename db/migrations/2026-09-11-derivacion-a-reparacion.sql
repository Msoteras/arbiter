-- 2026-09-11 · Referral to a repair shop: PENDING_REPAIR (pauses the art. 56 term, not final), provider_type
-- on expert_firm and expert_assessment, and repair_outcome apart from verdict, which is fraud vocabulary.
-- One referral per case and provider type. Apply before deploying the code (ddl-auto=validate). Idempotent.

BEGIN;

INSERT INTO arbiter_common.case_status (id, name, description, insured_status, is_final)
VALUES (9, 'PENDING_REPAIR', 'Derivado a servicio técnico, esperando su respuesta',
        'En reparación', FALSE)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('arbiter_common.case_status', 'id'),
              (SELECT MAX(id) FROM arbiter_common.case_status));

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format(
            'ALTER TABLE %I.expert_firm
                ADD COLUMN IF NOT EXISTS provider_type VARCHAR(20) NOT NULL
                    DEFAULT ''ESTUDIO_LIQUIDADOR''', tenant);

        EXECUTE format(
            'ALTER TABLE %I.expert_firm DROP CONSTRAINT IF EXISTS expert_firm_provider_type_valid',
            tenant);
        EXECUTE format(
            'ALTER TABLE %I.expert_firm ADD CONSTRAINT expert_firm_provider_type_valid
                CHECK (provider_type IN (''ESTUDIO_LIQUIDADOR'', ''SERVICIO_TECNICO''))', tenant);

        EXECUTE format(
            'ALTER TABLE %I.expert_assessment
                ADD COLUMN IF NOT EXISTS provider_type VARCHAR(20) NOT NULL
                    DEFAULT ''ESTUDIO_LIQUIDADOR'',
                ADD COLUMN IF NOT EXISTS repair_outcome VARCHAR(20)', tenant);

        -- The old unique on case_id alone blocks a second referral to another kind of provider.
        EXECUTE format(
            'ALTER TABLE %I.expert_assessment DROP CONSTRAINT IF EXISTS expert_assessment_case_unique',
            tenant);
        EXECUTE format(
            'ALTER TABLE %I.expert_assessment ADD CONSTRAINT expert_assessment_case_unique
                UNIQUE (case_id, provider_type)', tenant);

        EXECUTE format(
            'ALTER TABLE %I.expert_assessment
                DROP CONSTRAINT IF EXISTS expert_assessment_provider_type_valid', tenant);
        EXECUTE format(
            'ALTER TABLE %I.expert_assessment ADD CONSTRAINT expert_assessment_provider_type_valid
                CHECK (provider_type IN (''ESTUDIO_LIQUIDADOR'', ''SERVICIO_TECNICO''))', tenant);

        EXECUTE format(
            'ALTER TABLE %I.expert_assessment
                DROP CONSTRAINT IF EXISTS expert_assessment_repair_outcome_valid', tenant);
        EXECUTE format(
            'ALTER TABLE %I.expert_assessment ADD CONSTRAINT expert_assessment_repair_outcome_valid
                CHECK (repair_outcome IS NULL
                       OR repair_outcome IN (''REPAIRED'', ''IRREPARABLE'', ''QUOTE_SENT''))', tenant);

        -- The old check required a verdict on every response; now each kind returns its own.
        EXECUTE format(
            'ALTER TABLE %I.expert_assessment
                DROP CONSTRAINT IF EXISTS expert_assessment_report_complete', tenant);
        EXECUTE format(
            'ALTER TABLE %I.expert_assessment ADD CONSTRAINT expert_assessment_report_complete
                CHECK ((report_received_at IS NULL AND verdict IS NULL AND repair_outcome IS NULL)
                       OR (report_received_at IS NOT NULL AND provider_type = ''ESTUDIO_LIQUIDADOR''
                           AND verdict IS NOT NULL AND repair_outcome IS NULL)
                       OR (report_received_at IS NOT NULL AND provider_type = ''SERVICIO_TECNICO''
                           AND repair_outcome IS NOT NULL AND verdict IS NULL))', tenant);

    END LOOP;
END $$;

COMMIT;

-- Check: the new status and columns, one row per insurer.
SELECT name, insured_status, is_final FROM arbiter_common.case_status WHERE name = 'PENDING_REPAIR';

SELECT table_schema, table_name, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE column_name IN ('provider_type', 'repair_outcome')
   AND table_name IN ('expert_firm', 'expert_assessment')
 ORDER BY table_schema, table_name, column_name;
