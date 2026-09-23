-- =============================================================================
-- Arbiter — DESTRUCTIVE reset of the multi-tenant database
--
-- ⚠️  DELETES ALL DATA. For development databases only; never run it against real data.
--
-- init-multitenant.sql only creates and fails on a populated database, so this drops
-- every Arbiter schema to let it run again. Full sequence:
--
--   psql "$DATABASE_PUBLIC_URL" -f db/reset-multitenant.sql
--   psql "$DATABASE_PUBLIC_URL" -f db/init-multitenant.sql
--   psql "$DATABASE_PUBLIC_URL" -f db/seed-demo.sql
--
-- On Railway use DATABASE_PUBLIC_URL: DATABASE_URL points at *.railway.internal, which
-- only resolves inside Railway's network.
--
-- Extensions in `public` are left alone: recreating them usually needs a superuser.
-- =============================================================================

BEGIN;

-- Schemas are discovered, not listed: tenant schemas grow with every onboarded insurer.
-- CASCADE because there are cross-schema FKs.
DO $$
DECLARE
    schema_name TEXT;
BEGIN
    FOR schema_name IN
        SELECT nspname
          FROM pg_namespace
         WHERE nspname = 'arbiter_common'
            OR nspname LIKE 'arbiter\_%'
            OR nspname LIKE 'aseguradora\_%'
    LOOP
        RAISE NOTICE 'Dropping schema %', schema_name;
        EXECUTE format('DROP SCHEMA IF EXISTS %I CASCADE', schema_name);
    END LOOP;
END $$;

COMMIT;

-- Should return no rows.
SELECT nspname AS esquemas_que_quedaron
  FROM pg_namespace
 WHERE nspname = 'arbiter_common'
    OR nspname LIKE 'arbiter\_%'
    OR nspname LIKE 'aseguradora\_%';
