-- 2026-09-25 · created_by (NOT NULL, arbiter_common.users) on insurer_rule and scoring_configuration,
-- backfilled with the referente of the first recorded change or the insurer's first referente. Apply
-- before deploying rules-service. Afterwards drop arbiter_common.create_tenant_schema(TEXT) and run the
-- CREATE OR REPLACE FUNCTION block from init-multitenant.sql, which takes p_created_by (done on Railway on
-- 25/09/2026). Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format(
            'ALTER TABLE %I.insurer_rule
                ADD COLUMN IF NOT EXISTS created_by BIGINT REFERENCES arbiter_common.users(id)',
            tenant);

        EXECUTE format(
            'ALTER TABLE %I.scoring_configuration
                ADD COLUMN IF NOT EXISTS created_by BIGINT REFERENCES arbiter_common.users(id)',
            tenant);

        EXECUTE format($sql$
            UPDATE %1$I.insurer_rule r
               SET created_by = COALESCE(
                       (SELECT ir.user_id
                          FROM %1$I.insurer_rule_history h
                          JOIN %1$I.insurer_referent ir ON ir.id = h.changed_by
                         WHERE h.rule_id = r.id
                         ORDER BY h.valid_from, h.id LIMIT 1),
                       (SELECT ir.user_id FROM %1$I.insurer_referent ir ORDER BY ir.id LIMIT 1))
             WHERE r.created_by IS NULL
            $sql$, tenant);

        EXECUTE format($sql$
            UPDATE %1$I.scoring_configuration c
               SET created_by = COALESCE(
                       (SELECT ir.user_id
                          FROM %1$I.scoring_configuration_history h
                          JOIN %1$I.insurer_referent ir ON ir.id = h.changed_by
                         WHERE h.scoring_configuration_id = c.id
                         ORDER BY h.valid_from, h.id LIMIT 1),
                       (SELECT ir.user_id FROM %1$I.insurer_referent ir ORDER BY ir.id LIMIT 1))
             WHERE c.created_by IS NULL
            $sql$, tenant);

        EXECUTE format('ALTER TABLE %I.insurer_rule ALTER COLUMN created_by SET NOT NULL', tenant);
        EXECUTE format('ALTER TABLE %I.scoring_configuration ALTER COLUMN created_by SET NOT NULL', tenant);

    END LOOP;
END $$;

COMMIT;

-- Check: who each rule is attributed to, per insurer.
DO $$
DECLARE
    tenant TEXT;
    row RECORD;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        FOR row IN EXECUTE format(
                'SELECT u.email, count(*) AS rules
                   FROM %I.insurer_rule r JOIN arbiter_common.users u ON u.id = r.created_by
                  GROUP BY u.email ORDER BY u.email', tenant) LOOP
            RAISE NOTICE '% · % regla(s) creadas por %', tenant, row.rules, row.email;
        END LOOP;
    END LOOP;
END $$;
