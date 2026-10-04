-- 2026-09-23 · document_analysis.extraction_status: COMPLETE, PARTIAL (only the transcription survived) or
-- FAILED. Existing rows whose transcription is raw JSON become FAILED; re-analyze those cases. Run before
-- deploying classification-service (ddl-auto=validate). Idempotent.

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
