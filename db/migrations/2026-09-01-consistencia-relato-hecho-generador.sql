-- =============================================================================
-- 2026-09-01 · llm_analysis: veredicto de consistencia del relato
--
-- Migración de ESQUEMA, aditiva e idempotente. Aplicar sobre una base que ya
-- tiene datos (Railway) sin pasar por el trío reset → init → seed.
--
-- El asegurado elige el hecho generador de un selector y escribe el relato
-- aparte. Nada cruzaba las dos cosas: las reglas duras evalúan el hecho
-- DECLARADO, así que quien elegía "Robo en vía pública" (cubierto) y describía
-- un hurto (excluido) pasaba el gate de exclusiones sin que nadie lo notara.
-- El modelo ahora contesta ese cruce y las tres columnas lo auditan:
--
--     cause_consistency      MATCHES | AMBIGUOUS | CONTRADICTS
--     suggested_claim_cause  el hecho que el relato describe, por nombre
--     cause_evidence         la frase textual del asegurado que lo sostiene
--
-- Las tres son NULL en todas las filas existentes, y eso es correcto: son
-- corridas anteriores al chequeo. NULL significa "no evaluado", nunca MATCHES —
-- el backend y el frontend lo tratan así, para no mostrarle al analista una
-- verificación que nadie hizo.
--
-- suggested_claim_cause guarda el NOMBRE y no un FK a arbiter_common.claim_cause
-- a propósito: llm_analysis es evidencia inmutable (Disposición SSN 2/2023) y
-- tiene que seguir diciendo lo que el modelo contestó aunque el referente
-- después renombre o borre ese hecho generador.
--
-- ORDEN: aplicar ANTES de desplegar. `hibernate.ddl-auto: validate` compara la
-- entidad contra la tabla al arrancar, así que sin esto classification-service
-- y cases-service no levantan.
--
-- Idempotente: IF NOT EXISTS en cada columna, y el CHECK se agrega sólo si falta.
-- =============================================================================

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

        -- ADD CONSTRAINT no tiene IF NOT EXISTS: se pregunta antes.
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

-- Verificación: las tres columnas en cada esquema de aseguradora.
SELECT table_schema, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'llm_analysis'
   AND column_name IN ('cause_consistency', 'suggested_claim_cause', 'cause_evidence')
 ORDER BY table_schema, column_name;
