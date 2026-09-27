-- 2026-08-30 · rule_result records the coverage scope rules (covers_family_group, claim_exhausts_coverage):
-- rule_id becomes nullable, since they are coverage columns and not insurer_rule rows, and rule_type
-- grows to 40. Apply before deploying the code (ddl-auto=validate). Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format(
            'ALTER TABLE %I.rule_result ALTER COLUMN rule_id DROP NOT NULL', tenant);

        EXECUTE format(
            'ALTER TABLE %I.rule_result ALTER COLUMN rule_type TYPE VARCHAR(40)', tenant);

    END LOOP;
END $$;

COMMIT;

-- Check: two rows per insurer, rule_id nullable and rule_type at 40.
SELECT table_schema, column_name, data_type, character_maximum_length, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'rule_result'
   AND column_name IN ('rule_id', 'rule_type')
 ORDER BY table_schema, column_name;
