-- 2026-10-13 · Drops document_requirement.mandatory. Every row of the schedule is required: the
-- column was always TRUE and no read ever looked at it. What a claim can file with less is the Fast
-- Track list, which lives in that rule's configuration.
-- Refuses to run while a row is FALSE: dropping the column would turn it into a required document.
-- Idempotent. Safe to apply after deploying the code: the entity no longer maps the column, and
-- its DEFAULT TRUE covers the inserts made in between.

BEGIN;

DO $$
DECLARE
    tenant      TEXT;
    not_required BIGINT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = tenant AND table_name = 'document_requirement'
                   AND column_name = 'mandatory') THEN
            RAISE NOTICE '%: ya no tiene la columna', tenant;
            CONTINUE;
        END IF;

        EXECUTE format($sql$
            SELECT count(*) FROM %1$I.document_requirement WHERE NOT mandatory
        $sql$, tenant) INTO not_required;

        IF not_required > 0 THEN
            RAISE EXCEPTION '%: % filas de la agenda no son obligatorias; borralas o marcalas antes de seguir',
                tenant, not_required;
        END IF;

        EXECUTE format($sql$
            ALTER TABLE %1$I.document_requirement DROP COLUMN IF EXISTS mandatory;
        $sql$, tenant);

        RAISE NOTICE '%: columna borrada', tenant;
    END LOOP;
END $$;

COMMIT;
