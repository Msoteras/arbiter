-- =============================================================================
-- 2026-09-11 · Derivación a reparación (servicio técnico)
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Agrega:
--   · arbiter_common.case_status  → la fila PENDING_REPAIR (estado PAUSANTE, no final:
--     el expediente espera al proveedor y el plazo del art. 56 queda interrumpido,
--     igual que con PENDING_EXPERT_REPORT).
--   · <tenant>.expert_firm        → provider_type, para que el mismo catálogo tenga
--     estudios liquidadores y servicios técnicos.
--   · <tenant>.expert_assessment  → provider_type + repair_outcome, y el unique pasa
--     de (case_id) a (case_id, provider_type): un expediente puede ir al perito,
--     volver sin fraude, y recién entonces al servicio técnico.
--
-- El resultado de la reparación va en columna propia y NO en `verdict`: ese es
-- vocabulario de fraude y un FRAUD_CONFIRMED le deja el antecedente al asegurado.
--
-- IMPORTANTE: los servicios corren con ddl-auto=validate. Aplicar ANTES de
-- desplegar el código que declara los campos, o cases-service no levanta.
-- `init-multitenant.sql` ya quedó actualizado para las bases nuevas.
--
-- Idempotente: se puede correr más de una vez sin romper nada.
-- =============================================================================

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

        -- El unique viejo es por (case_id) a secas: bloquea la segunda derivación aunque sea a
        -- otro tipo de proveedor, que es justo lo que esta historia habilita.
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

        -- El viejo exigía `verdict` para toda devolución. Ahora cada tipo vuelve con el suyo.
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

-- Verificación: el estado nuevo y las columnas nuevas, una fila por aseguradora.
SELECT name, insured_status, is_final FROM arbiter_common.case_status WHERE name = 'PENDING_REPAIR';

SELECT table_schema, table_name, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE column_name IN ('provider_type', 'repair_outcome')
   AND table_name IN ('expert_firm', 'expert_assessment')
 ORDER BY table_schema, table_name, column_name;
