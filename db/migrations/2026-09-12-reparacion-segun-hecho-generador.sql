-- 2026-09-12 · Data: REPAIR_DERIVATION rules saying which claim causes of a branch allow a repair referral
-- (Celulares: Rotura accidental, Caída; Tec. Portátil: Daño accidental). Causes are matched by name, with
-- U&'...' escapes because a PowerShell pipe once turned an accent into '?'. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format($sql$
            INSERT INTO %I.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                         blocks_fast_track, branch_id, coverage_id, configuration)
            SELECT TRUE, '2026-01-01 00:00:00+00',
                   U&'Derivar a reparaci\00F3n: rotura accidental y ca\00EDda', 'REPAIR_DERIVATION',
                   'DERIVAR', 1, FALSE, 1, NULL,
                   jsonb_build_object('claimCauseIds', (
                       SELECT jsonb_agg(id ORDER BY id) FROM arbiter_common.claim_cause
                       WHERE branch_id = 1 AND name IN ('Rotura accidental', U&'Ca\00EDda')))
            WHERE NOT EXISTS (
                    SELECT 1 FROM %I.insurer_rule
                    WHERE rule_type = 'REPAIR_DERIVATION' AND branch_id = 1 AND coverage_id IS NULL)
              AND EXISTS (
                    SELECT 1 FROM arbiter_common.claim_cause
                    WHERE branch_id = 1 AND name IN ('Rotura accidental', U&'Ca\00EDda'))
            $sql$, tenant, tenant);

        EXECUTE format($sql$
            INSERT INTO %I.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                         blocks_fast_track, branch_id, coverage_id, configuration)
            SELECT TRUE, '2026-01-01 00:00:00+00',
                   U&'Derivar a reparaci\00F3n: da\00F1o accidental', 'REPAIR_DERIVATION',
                   'DERIVAR', 1, FALSE, 2, NULL,
                   jsonb_build_object('claimCauseIds', (
                       SELECT jsonb_agg(id ORDER BY id) FROM arbiter_common.claim_cause
                       WHERE branch_id = 2 AND name = U&'Da\00F1o accidental'))
            WHERE NOT EXISTS (
                    SELECT 1 FROM %I.insurer_rule
                    WHERE rule_type = 'REPAIR_DERIVATION' AND branch_id = 2 AND coverage_id IS NULL)
              AND EXISTS (
                    SELECT 1 FROM arbiter_common.claim_cause
                    WHERE branch_id = 2 AND name = U&'Da\00F1o accidental')
            $sql$, tenant, tenant);

    END LOOP;
END $$;

COMMIT;

-- Check: one row per insurer and branch, with the ids of its causes.
SELECT 'bbva' AS tenant, branch_id, name, configuration
  FROM arbiter_bbva.insurer_rule WHERE rule_type = 'REPAIR_DERIVATION'
UNION ALL
SELECT 'provincia', branch_id, name, configuration
  FROM arbiter_provincia.insurer_rule WHERE rule_type = 'REPAIR_DERIVATION'
ORDER BY 1, 2;
