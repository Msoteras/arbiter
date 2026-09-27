-- 2026-09-20 · settlement_authority.updated_by: who last changed the ceiling. Existing rows stay NULL,
-- meaning "unknown". Apply before deploying the code (ddl-auto=validate). Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format(
            'ALTER TABLE %I.settlement_authority
                ADD COLUMN IF NOT EXISTS updated_by BIGINT REFERENCES arbiter_common.users(id)',
            tenant);

    END LOOP;
END $$;

COMMIT;

-- Check: the new column, one row per insurer.
SELECT table_schema, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'settlement_authority' AND column_name = 'updated_by'
 ORDER BY table_schema;
