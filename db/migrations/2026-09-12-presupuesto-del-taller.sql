-- =============================================================================
-- 2026-09-12 · El presupuesto que informa el servicio técnico
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Agrega:
--   · <tenant>.expert_assessment → quoted_amount, el precio que el taller puso a
--     la reparación.
--
-- Va en columna propia y NO en `indemnifiable_amount` por la misma razón por la
-- que el resultado de la reparación no va en `verdict`: los dos números contestan
-- preguntas distintas. El perito dice cuánto VALE el siniestro —una opinión sobre
-- la indemnización—; el taller dice cuánto CUESTA arreglarlo —un precio, que es
-- la base sobre la que liquida la fórmula de reparación—. Guardarlos juntos
-- obligaría a mirar provider_type para saber qué significa el número.
--
-- El CHECK de completitud se reescribe para incluirlo: un QUOTE_SENT sin importe
-- no es una respuesta, y un equipo reparado o irreparable no tiene presupuesto
-- que informar. Del lado del peritaje, quoted_amount siempre es NULL.
--
-- IMPORTANTE: los servicios corren con ddl-auto=validate. Aplicar ANTES de
-- desplegar el código que declara el campo, o cases-service no levanta.
-- `init-multitenant.sql` ya quedó actualizado para las bases nuevas.
--
-- Idempotente: se puede correr más de una vez sin romper nada.
-- =============================================================================

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

-- Verificación: la columna nueva, una fila por aseguradora.
SELECT table_schema, table_name, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE column_name = 'quoted_amount' AND table_name = 'expert_assessment'
 ORDER BY table_schema;
