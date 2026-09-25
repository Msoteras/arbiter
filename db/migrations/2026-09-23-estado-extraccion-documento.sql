-- =============================================================================
-- 2026-09-23 · Si la lectura del documento funcionó
--
-- Agrega document_analysis.extraction_status: COMPLETE (se leyó bien), PARTIAL (solo se
-- rescató la transcripción: la respuesta del modelo llegó cortada y se perdieron los datos)
-- o FAILED (no se pudo leer nada).
--
-- Para qué: cuando la respuesta del modelo llegaba rota (cortada por el tope de tokens, o
-- en loop repitiendo dígitos), se guardaba el JSON crudo como transcripción y los campos
-- vacíos. Un campo vacío significa "el documento no lo dice", así que el analista veía
-- "No lo aclara el documento" como si fuera un resultado real, y las reglas que comparan
-- marca, IMEI o hecho generador se quedaban afuera sin avisar.
--
-- Las filas existentes quedan COMPLETE, salvo las que guardaron el JSON crudo como
-- transcripción (empiezan con '{'): esas son lecturas rotas y se marcan FAILED. Conviene
-- re-analizar esos casos.
--
-- Hay que correrla ANTES de desplegar classification-service: con ddl-auto=validate, el
-- servicio no levanta si la columna no existe.
--
-- Idempotente: se puede correr más de una vez.
--
-- Uso:
--   psql "$DATABASE_URL" -f db/migrations/2026-09-23-estado-extraccion-documento.sql
-- =============================================================================

BEGIN;

DO $$
DECLARE
    tenant TEXT;
    broken INTEGER;
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
            'ALTER TABLE %I.document_analysis '
            || 'ADD COLUMN IF NOT EXISTS extraction_status VARCHAR(20) NOT NULL DEFAULT ''COMPLETE''',
            tenant);

        IF NOT EXISTS (
            SELECT 1 FROM information_schema.table_constraints
             WHERE table_schema = tenant
               AND constraint_name = 'document_analysis_extraction_status_valid'
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I.document_analysis ADD CONSTRAINT document_analysis_extraction_status_valid '
                || 'CHECK (extraction_status IN (''COMPLETE'', ''PARTIAL'', ''FAILED''))',
                tenant);
        END IF;

        EXECUTE format(
            'UPDATE %I.document_analysis SET extraction_status = ''FAILED'' '
            || 'WHERE extraction_status = ''COMPLETE'' AND transcription LIKE ''{%%''',
            tenant);
        GET DIAGNOSTICS broken = ROW_COUNT;
        RAISE NOTICE '%: document_analysis.extraction_status OK (% lectura(s) rota(s) marcadas FAILED)',
            tenant, broken;
    END LOOP;
END $$;

COMMIT;
