-- 2026-10-14 · Document types in the database, step 2 of 2. Run ONLY once every module that reads
-- document_requirement runs the code that maps document_type_id (rules-service above all): the
-- code before it still reads the free-text document_type that this drops.
--   · Drops document_requirement.document_type and the trigger that kept it in step.
-- Afterwards run the CREATE OR REPLACE FUNCTION arbiter_common.create_tenant_schema block from
-- init-multitenant.sql, or a new insurer is created with the old schedule and no
-- branch_document_type.
-- Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = tenant AND table_name = 'document_requirement'
                   AND column_name = 'document_type_id') THEN
            RAISE EXCEPTION '%: falta correr el paso 1 (2026-10-14-tipos-de-documento.sql)', tenant;
        END IF;

        EXECUTE format($sql$
            DROP TRIGGER IF EXISTS document_requirement_sync_type ON %1$I.document_requirement;
            ALTER TABLE %1$I.document_requirement DROP COLUMN IF EXISTS document_type;
        $sql$, tenant);

        RAISE NOTICE '%: columna vieja borrada', tenant;
    END LOOP;
END $$;

DROP FUNCTION IF EXISTS arbiter_common.sync_document_requirement_type();

COMMIT;
