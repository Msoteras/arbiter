-- 2026-10-21 · One user is at most one insured, one claims analyst and one referent per insurer:
-- UNIQUE (user_id) on insured, claims_analyst and insurer_referent. Before, bulk provisioning could
-- hang a second insured (another DNI, same email) off an existing user, who then saw both people's
-- policies. Stops without changing anything if a user already has two rows in one table.
-- Afterwards run the CREATE OR REPLACE FUNCTION arbiter_common.create_tenant_schema block from
-- init-multitenant.sql, or a new insurer is created without these constraints.
-- Idempotent. Safe before or after deploying the code: the code never writes a second row.

BEGIN;

DO $$
DECLARE
    tenant    TEXT;
    tbl       TEXT;
    repeated  TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        FOREACH tbl IN ARRAY ARRAY['insured', 'claims_analyst', 'insurer_referent'] LOOP
            IF EXISTS (
                    SELECT 1 FROM pg_constraint
                     WHERE conname = tbl || '_user_id_key'
                       AND conrelid = format('%I.%I', tenant, tbl)::regclass) THEN
                RAISE NOTICE '%.%: ya tiene la restricción', tenant, tbl;
                CONTINUE;
            END IF;

            EXECUTE format(
                'SELECT string_agg(user_id::TEXT, '', '') FROM (
                     SELECT user_id FROM %I.%I GROUP BY user_id HAVING count(*) > 1) d',
                tenant, tbl)
                INTO repeated;
            IF repeated IS NOT NULL THEN
                RAISE EXCEPTION '%.%: usuarios con más de una fila (%). Resolverlos antes de migrar.',
                    tenant, tbl, repeated;
            END IF;

            EXECUTE format('ALTER TABLE %I.%I ADD CONSTRAINT %I UNIQUE (user_id)',
                tenant, tbl, tbl || '_user_id_key');
            RAISE NOTICE '%.%: restricción agregada', tenant, tbl;
        END LOOP;
    END LOOP;
END $$;

COMMIT;
