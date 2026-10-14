-- 2026-10-10 · A service provider with no branches stops meaning "covers every branch": it now covers
-- none, and the referent picks each branch it takes. Data only, no schema change.
-- To keep today's behaviour, every provider without branches gets all of its insurer's branches.
-- Apply before deploying cases-service, or those providers drop out of the analyst's selector.
-- Idempotent: providers that already have a branch are left alone.

BEGIN;

DO $$
DECLARE
    tenant  TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        EXECUTE format(
            'INSERT INTO %I.service_provider_branch (service_provider_id, branch_id)
             SELECT p.id, b.id
             FROM %I.service_provider p
             CROSS JOIN %I.branch b
             WHERE NOT EXISTS (SELECT 1 FROM %I.service_provider_branch spb
                               WHERE spb.service_provider_id = p.id)',
            tenant, tenant, tenant, tenant);
    END LOOP;
END $$;

COMMIT;
