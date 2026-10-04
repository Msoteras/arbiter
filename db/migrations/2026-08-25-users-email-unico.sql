-- 2026-08-25 · Unique index on arbiter_common.users.email: bulk provisioning dedupes people by email.
-- It fails if duplicates already exist, so step 1 checks first.

-- Step 1: any duplicates? If this returns rows, stop and fix them by hand.
SELECT lower(email) AS email, COUNT(*) AS veces,
       array_agg(id ORDER BY id) AS ids
  FROM arbiter_common.users
 GROUP BY lower(email)
HAVING COUNT(*) > 1;

-- Step 2: the index, on lower(email) since the login treats both cases as the same address.
BEGIN;

CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower_uq
    ON arbiter_common.users (lower(email));

COMMIT;

-- Check.
SELECT indexname, indexdef
  FROM pg_indexes
 WHERE schemaname = 'arbiter_common'
   AND tablename = 'users'
   AND indexname = 'users_email_lower_uq';
