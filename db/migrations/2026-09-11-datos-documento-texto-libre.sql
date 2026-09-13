-- =============================================================================
-- document_analysis: marca, modelo y bien, sin tope de largo
--
-- El 11/09 el expediente 42 de BBVA se clasificó y perdió las extracciones de sus
-- cuatro documentos: el INSERT falló con "value too long for type character
-- varying(100)" porque el modelo devolvió un `model` más largo que la columna, y
-- ClassificationOrchestrator atrapa ese error para no voltear la clasificación —
-- así que la pérdida es SILENCIOSA. En pantalla se ve un expediente clasificado
-- sin un dato de sus documentos, y nada dice por qué.
--
-- Estos tres campos son texto libre que devuelve un modelo de visión, así que van
-- a TEXT, igual que `transcription` y que `llm_analysis.cause_evidence`. No se
-- truncan a propósito: `DocumentInconsistencyEvaluator` COMPARA marca y modelo
-- contra el bien asegurado, y un valor cortado no es un dato incompleto, es un
-- dato equivocado que puede levantar un hallazgo falso contra el asegurado.
--
-- Idempotente: cambiar el tipo de una columna que ya es TEXT no hace nada.
-- No pierde datos (VARCHAR → TEXT es una ampliación) y no reescribe la tabla.
--
-- Uso:
--   psql "$DATABASE_URL" -f db/migrations/2026-09-11-datos-documento-texto-libre.sql
-- =============================================================================

BEGIN;

DO $$
DECLARE
    tenant TEXT;
    columna TEXT;
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

        FOREACH columna IN ARRAY ARRAY['brand', 'model', 'item_description']
        LOOP
            IF EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = tenant AND table_name = 'document_analysis'
                   AND column_name = columna AND data_type <> 'text'
            ) THEN
                EXECUTE format('ALTER TABLE %I.document_analysis ALTER COLUMN %I TYPE TEXT',
                               tenant, columna);
            END IF;
        END LOOP;

        RAISE NOTICE 'Migrated %', tenant;
    END LOOP;
END $$;

COMMIT;

-- ─── Verificación ────────────────────────────────────────────────────────────
--
-- Los tres campos en text, por tenant (tres filas por esquema):
--
-- SELECT table_schema, column_name, data_type, character_maximum_length
--   FROM information_schema.columns
--  WHERE table_name = 'document_analysis'
--    AND column_name IN ('brand', 'model', 'item_description')
--  ORDER BY table_schema, column_name;
--
-- Y que el expediente que lo destapó ya tenga sus extracciones, después de
-- reclasificarlo:
--
-- SELECT d.type, a.brand, a.model, length(a.item_description)
--   FROM arbiter_bbva.document_analysis a
--   JOIN arbiter_bbva.case_documents d ON d.id = a.case_document_id
--  WHERE d.case_id = 42;
