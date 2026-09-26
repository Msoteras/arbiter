-- 2026-08-28 · policy_snapshot.total_amount_claimed. Nullable on purpose: older snapshots cannot know
-- it and 0 would read as "never claimed". Apply before deploying the code (ddl-auto=validate).

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format(
            'ALTER TABLE %I.policy_snapshot ADD COLUMN IF NOT EXISTS total_amount_claimed NUMERIC(15,2)',
            tenant);

    END LOOP;
END $$;

COMMIT;

-- Check: one row per insurer.
SELECT table_schema, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'policy_snapshot'
   AND column_name = 'total_amount_claimed'
 ORDER BY table_schema;
