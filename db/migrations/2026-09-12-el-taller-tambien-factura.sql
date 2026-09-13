-- =============================================================================
-- 2026-09-12 · El taller también factura
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Cambia, sobre <tenant>.expert_assessment:
--   · quoted_amount → repair_cost. El importe ya no es sólo un presupuesto: cuando
--     el taller arregla el equipo, lo que informa es lo que cobró. Las dos cosas
--     son lo mismo para la liquidación —cuánto sale el arreglo— pero "quoted" sólo
--     nombraba una.
--   · El CHECK de completitud, para que el importe acompañe a los dos resultados
--     que tienen trabajo detrás.
--
-- Qué resultado admite qué importe:
--   · QUOTE_SENT   → obligatorio. Decir que mandaron presupuesto sin decir cuánto
--                    no contesta la pregunta que se les hizo.
--   · REPAIRED     → opcional. Hay factura, pero puede llegar después del informe.
--   · IRREPARABLE  → prohibido. No hubo trabajo que cobrar.
--
-- Va sobre 2026-09-12-presupuesto-del-taller.sql, que creó la columna. Si esa no
-- corrió, correla antes: ésta renombra, no crea.
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

-- Verificación: la columna renombrada, una fila por aseguradora, y ninguna quoted_amount.
SELECT table_schema, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'expert_assessment' AND column_name IN ('repair_cost', 'quoted_amount')
 ORDER BY table_schema, column_name;
