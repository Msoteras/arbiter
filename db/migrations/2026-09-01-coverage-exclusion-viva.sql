-- 2026-09-01 · The seed's coverage rules used COVERAGE_INCLUSION, a type the code never reads, so no
-- exclusion applied. They become COVERAGE_EXCLUSION blacklists: each coverage covers one claim cause.
-- Celulares: 1 Rotura accidental, 2 Robo en vía pública, 3 Hurto, 4 Caída.
-- Tec. Portátil: 6 Daño accidental, 7 Robo en vía pública, 8 Hurto. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN
        SELECT schema_name FROM arbiter_common.insurer
    LOOP
        -- 1. Old whitelist rows become blacklists. Robo de celular (coverage 1) covers only 2.
        EXECUTE format($dml$
            UPDATE %I.insurer_rule
               SET rule_type     = 'COVERAGE_EXCLUSION',
                   name          = 'La cobertura de robo solo cubre robo en vía pública',
                   configuration = '{"excludedClaimCauseIds":[1,3,4]}'
             WHERE rule_type = 'COVERAGE_INCLUSION'
               AND coverage_id = 1
        $dml$, tenant);

        --    Hurto (coverage 2) covers only 3.
        EXECUTE format($dml$
            UPDATE %I.insurer_rule
               SET rule_type     = 'COVERAGE_EXCLUSION',
                   name          = 'La cobertura de hurto solo cubre hurto',
                   configuration = '{"excludedClaimCauseIds":[1,2,4]}'
             WHERE rule_type = 'COVERAGE_INCLUSION'
               AND coverage_id = 2
        $dml$, tenant);

        -- 2. Any other COVERAGE_INCLUSION is deactivated: nothing reads it.
        EXECUTE format($dml$
            UPDATE %I.insurer_rule SET active = FALSE WHERE rule_type = 'COVERAGE_INCLUSION'
        $dml$, tenant);

        -- 3. Daño accidental (coverage 3, branch 2) had no rule; only where that coverage exists.
        EXECUTE format($dml$
            INSERT INTO %I.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                         blocks_fast_track, branch_id, coverage_id, configuration)
            SELECT TRUE, '2026-01-01 00:00:00+00',
                   'La cobertura de daño accidental solo cubre daño accidental',
                   'COVERAGE_EXCLUSION', 'RECHAZAR', 1, TRUE, 2, c.id,
                   '{"excludedClaimCauseIds":[7,8]}'
              FROM %I.coverage c
             WHERE c.name = 'Daño accidental'
               AND NOT EXISTS (
                   SELECT 1 FROM %I.insurer_rule r
                    WHERE r.coverage_id = c.id AND r.rule_type = 'COVERAGE_EXCLUSION')
        $dml$, tenant, tenant, tenant);
    END LOOP;
END $$;

COMMIT;

-- Check (per schema): each coverage lists the causes it does NOT cover.
-- SELECT c.name, r.rule_type, r.active, r.configuration
--   FROM arbiter_bbva.coverage c
--   LEFT JOIN arbiter_bbva.insurer_rule r ON r.coverage_id = c.id
--    AND r.rule_type = 'COVERAGE_EXCLUSION'
--  ORDER BY c.id;
