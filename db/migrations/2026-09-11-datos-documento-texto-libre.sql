-- 2026-09-11 · document_analysis brand, model and item_description become TEXT: a long model value made the
-- insert fail and the extraction was lost silently. Not truncated on purpose: they are compared with the
-- insured item, and a cut value is a wrong one. Idempotent, no table rewrite.

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

-- ─── Verification ────────────────────────────────────────────────────────────
--
-- The three columns as text, per tenant (three rows per schema):
--
-- SELECT table_schema, column_name, data_type, character_maximum_length
--   FROM information_schema.columns
--  WHERE table_name = 'document_analysis'
--    AND column_name IN ('brand', 'model', 'item_description')
--  ORDER BY table_schema, column_name;
--
-- And the case that exposed it, once reclassified:
--
-- SELECT d.type, a.brand, a.model, length(a.item_description)
--   FROM arbiter_bbva.document_analysis a
--   JOIN arbiter_bbva.case_documents d ON d.id = a.case_document_id
--  WHERE d.case_id = 42;
