-- 2026-09-28 · Robo de celular and Hurto stop answering for Rotura accidental and Caída. A coverage
-- answers for every cause it doesn't exclude, and ties go to the company's order, so with Robo first
-- every fall landed there and was settled as a stolen phone (total loss, replacement value, pending
-- installments). Brings Railway back to what init-multitenant.sql defines:
--   Robo de celular excludes 1 Rotura accidental, 3 Hurto, 4 Caída
--   Hurto           excludes 1 Rotura accidental, 2 Robo en vía pública, 4 Caída
-- A policy without Daño accidental stops offering those causes in the claim wizard: nothing covers them.
--
-- An existing rule keeps its previous version in insurer_rule_history, attributed to this migration
-- (changed_by NULL) rather than to a referent who didn't make the change. A missing rule (Provincia
-- never had these) is created with created_by = the insurer's first referente, as 2026-09-25 did.
-- Cases already filed keep the coverage they were given. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant   TEXT;
    cov      RECORD;
    rule     RECORD;
    now_ts   TIMESTAMPTZ := NOW();
    config   JSONB;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        FOR cov IN EXECUTE format(
                'SELECT id, name, branch_id FROM %I.coverage WHERE name IN (''Robo de celular'', ''Hurto'')',
                tenant) LOOP

            config := CASE cov.name
                          WHEN 'Robo de celular' THEN '{"excludedClaimCauseIds":[1,3,4]}'::jsonb
                          ELSE '{"excludedClaimCauseIds":[1,2,4]}'::jsonb
                      END;

            EXECUTE format(
                'SELECT id, active, blocks_fast_track, configuration, valid_from FROM %I.insurer_rule
                  WHERE coverage_id = $1 AND rule_type = ''COVERAGE_EXCLUSION'' ORDER BY id LIMIT 1',
                tenant) INTO rule USING cov.id;

            IF rule.id IS NULL THEN
                EXECUTE format(
                    'INSERT INTO %1$I.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                                    blocks_fast_track, branch_id, coverage_id, configuration,
                                                    created_by)
                     VALUES (TRUE, $1, $2, ''COVERAGE_EXCLUSION'', ''RECHAZAR'', 1, TRUE, $3, $4, $5,
                             (SELECT user_id FROM %1$I.insurer_referent ORDER BY id LIMIT 1))',
                    tenant)
                    USING now_ts,
                          CASE cov.name WHEN 'Robo de celular'
                              THEN 'La cobertura de robo solo cubre robo en vía pública'
                              ELSE 'La cobertura de hurto solo cubre hurto' END,
                          cov.branch_id, cov.id, config;
            ELSIF rule.configuration IS DISTINCT FROM config OR NOT rule.active THEN
                EXECUTE format(
                    'INSERT INTO %I.insurer_rule_history (config_version, changed_at, reason, valid_from,
                                                          valid_to, rule_id, changed_by)
                     VALUES ($1, $2, $3, $4, $2, $5, NULL)',
                    tenant)
                    USING jsonb_build_object('active', rule.active,
                                             'blocksFastTrack', rule.blocks_fast_track,
                                             'configuration', rule.configuration,
                                             'legacy', false),
                          now_ts,
                          'Exclusiones de cobertura actualizadas por la migración del 28/09/2026',
                          rule.valid_from, rule.id;
                EXECUTE format(
                    'UPDATE %I.insurer_rule SET configuration = $1, active = TRUE, valid_from = $2 WHERE id = $3',
                    tenant)
                    USING config, now_ts, rule.id;
            END IF;
            rule := NULL;
        END LOOP;
    END LOOP;
END $$;

COMMIT;

-- Check:
-- SELECT coverage_id, active, configuration FROM arbiter_bbva.insurer_rule
--  WHERE rule_type = 'COVERAGE_EXCLUSION' ORDER BY coverage_id;
