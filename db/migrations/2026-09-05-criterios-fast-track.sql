-- 2026-09-05 · Fast Track criteria (H0038), and the coverage BBVA Celulares was missing:
--   1. Fast Track thresholds as configuration, one row per coverage (they only lived in code).
--   2. Old gate rule_result rows re-expressed per criterion (FT_*).
--   3. BBVA Celulares gets Daño accidental: nothing covered Rotura accidental (1) or Caída (4).
--   4. Provincia: POL-CEL-2026-905 gets Hurto, and its theft case moves to it.
-- Tec. Portátil in Provincia has the same gap on purpose (docs/temas-a-discutir.md). Idempotent.

BEGIN;

-- 1 · Fast Track thresholds per coverage. Values are the MockRulesAdapter baseline, so no verdict
-- changes. No requiredDocumentTypes: the document agenda already checks that at filing.
DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN
        SELECT schema_name FROM arbiter_common.insurer
    LOOP
        -- Old rows without coverage are not deleted: rule_result points at them.
        EXECUTE format($dml$
            UPDATE %I.insurer_rule
               SET active = FALSE,
                   name   = name || ' (obsoleta: reemplazada por la config por cobertura)'
             WHERE rule_type = 'FAST_TRACK'
               AND coverage_id IS NULL
               AND active
        $dml$, tenant);

        -- Keyed by coverage name: ids differ per schema.
        EXECUTE format($dml$
            INSERT INTO %I.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                         blocks_fast_track, branch_id, coverage_id, configuration)
            SELECT TRUE, '2026-01-01 00:00:00+00', 'Fast Track — ' || c.name,
                   'FAST_TRACK', 'APROBAR', 1, FALSE, c.branch_id, c.id,
                   CASE
                       WHEN c.name = 'Robo de celular' THEN
                           '{"maxClaimedAmountRatio":0.5,"maxPriorClaims":0,"requiresUpToDatePolicy":true,
                             "criteria":["Primer siniestro del asegurado",
                                         "Monto reclamado inferior al 50%% de la suma asegurada",
                                         "Póliza al día con sus pagos"]}'
                       WHEN c.name = 'Hurto' THEN
                           '{"maxClaimedAmountRatio":0.3,"maxPriorClaims":0,"requiresUpToDatePolicy":true,
                             "criteria":["Primer siniestro del asegurado",
                                         "Monto reclamado inferior al 30%% de la suma asegurada",
                                         "Póliza al día con sus pagos"]}'
                       -- Higher cap: repairing a laptop costs a large share of a small sum insured.
                       WHEN b.name = 'Tecnología Portátil' THEN
                           '{"maxClaimedAmountRatio":0.6,"maxPriorClaims":0,"requiresUpToDatePolicy":true,
                             "criteria":["Primer siniestro del asegurado",
                                         "Monto reclamado inferior al 60%% de la suma asegurada",
                                         "Póliza al día con sus pagos"]}'
                       ELSE
                           '{"maxClaimedAmountRatio":0.5,"maxPriorClaims":0,"requiresUpToDatePolicy":true,
                             "criteria":["Primer siniestro del asegurado",
                                         "Monto reclamado inferior al 50%% de la suma asegurada",
                                         "Póliza al día con sus pagos"]}'
                   END::jsonb
              FROM %I.coverage c
              JOIN arbiter_common.branch b ON b.id = c.branch_id
             WHERE NOT EXISTS (
                   SELECT 1 FROM %I.insurer_rule r
                    WHERE r.rule_type = 'FAST_TRACK' AND r.coverage_id = c.id)
        $dml$, tenant, tenant, tenant);
    END LOOP;
END $$;

-- 2 · Old gate rule_result rows, one type per criterion as FastTrackValidator writes them, rule_id NULL.
-- Only rows that can be re-expressed without guessing; anything else stays as it is.
DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN
        SELECT schema_name FROM arbiter_common.insurer
    LOOP
        -- Claimed amount over sum insured; the cap comes from the coverage set up in part 1.
        EXECUTE format($dml$
            UPDATE %I.rule_result rr
               SET rule_type = 'FT_AMOUNT_RATIO',
                   rule_id   = NULL,
                   evaluated_value =
                       'ratio=' || to_char(round(rr.evaluated_value::numeric * 100, 1), 'FM990.0') || '%%'
                       || ' max=' || to_char(
                              round(((r.configuration->>'maxClaimedAmountRatio')::numeric) * 100, 1),
                              'FM990.0') || '%%'
              FROM %I.cases c
              JOIN %I.insurer_rule r
                ON r.rule_type = 'FAST_TRACK' AND r.coverage_id = c.coverage_id
             WHERE rr.case_id = c.id
               AND rr.rule_type = 'FAST_TRACK'
               AND rr.evaluated_value ~ '^[0-9]*\.?[0-9]+$'
        $dml$, tenant, tenant, tenant);

        -- Policy payment status.
        EXECUTE format($dml$
            UPDATE %I.rule_result
               SET rule_type = 'FT_POLICY_UP_TO_DATE',
                   rule_id   = NULL,
                   evaluated_value = CASE WHEN evaluated_value = 'AL_DIA'
                                          THEN 'upToDate=true' ELSE 'upToDate=false' END
             WHERE rule_type = 'FAST_TRACK'
               AND evaluated_value IN ('AL_DIA', 'SUSPENDIDA', 'RESUELTA')
        $dml$, tenant);

        -- Also rows left with the old whitelist type: the coverage-exclusion migration fixed the rules but not
        -- these results. Same meaning, CoverageRuleEvaluator's type and value format.
        EXECUTE format($dml$
            UPDATE %I.rule_result rr
               SET rule_type = 'COVERAGE_EXCLUSION',
                   evaluated_value = 'claimCause=' || cc.name || ' (id=' || cc.id || ')'
              FROM %I.cases c
              JOIN arbiter_common.claim_cause cc ON cc.id = c.claim_cause_id
             WHERE rr.case_id = c.id
               AND rr.rule_type = 'COVERAGE_INCLUSION'
               -- Some rows already carry CoverageRuleEvaluator's format and only the type is stale.
               AND rr.evaluated_value IN (cc.name, 'claimCause=' || cc.name || ' (id=' || cc.id || ')')
        $dml$, tenant, tenant);

        -- Any COVERAGE_INCLUSION without the cause name stays: its value cannot be rebuilt.
    END LOOP;
END $$;

-- 3 · BBVA: Daño accidental for Celulares (Provincia awaits a business decision).
DO $$
DECLARE
    tenant     TEXT := 'arbiter_bbva';
    aseg       TEXT := 'aseguradora_bbva';
    celulares  BIGINT;
    nueva      BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM arbiter_common.insurer WHERE schema_name = tenant) THEN
        RAISE NOTICE 'No existe el esquema %, se saltea la parte 3', tenant;
        RETURN;
    END IF;

    SELECT id INTO celulares FROM arbiter_common.branch WHERE name = 'Celulares';

    -- a) The coverage. 20% deductible, the usual premium over theft's 10%.
    EXECUTE format($dml$
        INSERT INTO %I.coverage (name, description, report_deadline_hours, max_events_per_year,
                                 covers_family_group, deductible, claim_exhausts_coverage,
                                 is_individual, waiting_period_days, branch_id)
        SELECT 'Daño accidental', 'Cobertura por rotura o caída accidental del equipo',
               72, 2, FALSE, 20.00, FALSE, TRUE, 30, $1
         WHERE NOT EXISTS (SELECT 1 FROM %I.coverage
                            WHERE name = 'Daño accidental' AND branch_id = $1)
    $dml$, tenant, tenant) USING celulares;

    EXECUTE format($q$SELECT id FROM %I.coverage WHERE name = 'Daño accidental' AND branch_id = $1$q$,
                   tenant) INTO nueva USING celulares;

    -- Damage settles as a repair (2026-09-06-formula-de-reparacion.sql). That migration applies it
    -- by name, so it only reaches this coverage if it runs after this one; on a database where it
    -- already ran, the coverage would be born TOTAL_LOSS. The column may not exist yet either,
    -- hence the guard.
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = tenant AND table_name = 'coverage'
                  AND column_name = 'settlement_formula') THEN
        EXECUTE format($dml$UPDATE %I.coverage SET settlement_formula = 'REPAIR' WHERE id = $1$dml$,
                       tenant) USING nueva;
    END IF;

    -- b) Its rules, like the other two coverages. No POLICE_DEADLINE: accidental damage has no police
    --    report. FAST_TRACK is added here if part 1 did not find the coverage.
    EXECUTE format($dml$
        INSERT INTO %I.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                     blocks_fast_track, branch_id, coverage_id, configuration)
        SELECT * FROM (VALUES
            (TRUE, '2026-01-01 00:00:00+00'::timestamptz,
             'La cobertura de daño accidental solo cubre rotura y caída',
             'COVERAGE_EXCLUSION', 'RECHAZAR', 1, TRUE, $1::bigint, $2::bigint,
             '{"excludedClaimCauseIds":[2,3]}'::jsonb),
            (TRUE, '2026-01-01 00:00:00+00'::timestamptz,
             'Fast Track — Daño accidental', 'FAST_TRACK', 'APROBAR', 1, FALSE, $1, $2,
             '{"maxClaimedAmountRatio":0.5,"maxPriorClaims":0,"requiresUpToDatePolicy":true,
               "criteria":["Primer siniestro del asegurado",
                           "Monto reclamado inferior al 50%% de la suma asegurada",
                           "Póliza al día con sus pagos"]}'::jsonb),
            (TRUE, '2026-01-01 00:00:00+00'::timestamptz,
             'Carencia de la cobertura (daño accidental)',
             'WAITING_PERIOD', 'DERIVAR', 2, TRUE, $1, $2, '{}'::jsonb),
            (TRUE, '2026-01-01 00:00:00+00'::timestamptz,
             'Plazo de denuncia a la aseguradora (daño accidental)',
             'REPORT_DEADLINE', 'DERIVAR', 3, TRUE, $1, $2, '{}'::jsonb),
            (TRUE, '2026-01-01 00:00:00+00'::timestamptz,
             'Tope de eventos por año (daño accidental)',
             'MAX_EVENTS_YEAR', 'DERIVAR', 5, TRUE, $1, $2, '{}'::jsonb)
        ) AS nueva(active, valid_from, name, rule_type, effect, priority,
                   blocks_fast_track, branch_id, coverage_id, configuration)
         WHERE NOT EXISTS (
               SELECT 1 FROM %I.insurer_rule r
                WHERE r.coverage_id = $2 AND r.rule_type = nueva.rule_type)
    $dml$, tenant, tenant) USING celulares, nueva;

    -- c) Premium policies get it for the same sum as theft; Básico ones do not, by product.
    EXECUTE format($dml$
        INSERT INTO %I.policy_coverage (policy_id, coverage_id, display_order, sum_insured, deductible_pct)
        SELECT p.id, $1, 3, robo.sum_insured, 20.00
          FROM %I.policy p
          JOIN %I.policy_coverage robo ON robo.policy_id = p.id
          JOIN %I.coverage rc ON rc.id = robo.coverage_id AND rc.name = 'Robo de celular'
         WHERE p.product ILIKE '%%Premium%%'
           AND NOT EXISTS (SELECT 1 FROM %I.policy_coverage pc
                            WHERE pc.policy_id = p.id AND pc.coverage_id = $1)
    $dml$, tenant, tenant, tenant, tenant, tenant) USING nueva;

    -- d) The insurer database mirror, where the engine reads the sum from.
    EXECUTE format($dml$
        INSERT INTO %I.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct)
        SELECT pz.id, 3, 'Daño accidental', robo.suma_asegurada, 20.00
          FROM %I.poliza pz
          JOIN %I.cobertura robo ON robo.poliza_id = pz.id AND robo.nombre = 'Robo de celular'
         WHERE pz.rama = 'Celulares'
           AND pz.producto ILIKE '%%Premium%%'
           AND NOT EXISTS (SELECT 1 FROM %I.cobertura c
                            WHERE c.poliza_id = pz.id AND c.nombre = 'Daño accidental')
    $dml$, aseg, aseg, aseg, aseg);

    -- e) Damage and drop cases move to it, only when their policy has it.
    EXECUTE format($dml$
        UPDATE %I.cases c
           SET coverage_id = $1
          FROM arbiter_common.claim_cause cc
         WHERE cc.id = c.claim_cause_id
           AND cc.name IN ('Rotura accidental', 'Caída')
           AND c.coverage_id <> $1
           AND EXISTS (SELECT 1 FROM %I.policy_coverage pc
                        WHERE pc.policy_id = c.policy_id AND pc.coverage_id = $1)
    $dml$, tenant, tenant) USING nueva;
END $$;

-- 4 · Provincia: the policy without theft coverage, and its case
DO $$
DECLARE
    tenant TEXT := 'arbiter_provincia';
    aseg   TEXT := 'aseguradora_provincia';
    poliza CONSTANT TEXT := 'POL-CEL-2026-905';
    hurto  BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM arbiter_common.insurer WHERE schema_name = tenant) THEN
        RAISE NOTICE 'No existe el esquema %, se saltea la parte 4', tenant;
        RETURN;
    END IF;

    EXECUTE format($q$SELECT id FROM %I.coverage WHERE name = 'Hurto'$q$, tenant) INTO hurto;
    IF hurto IS NULL THEN
        RAISE NOTICE 'El tenant % no tiene cobertura de Hurto, se saltea la parte 4', tenant;
        RETURN;
    END IF;

    -- Half of theft's sum, as in the insurer's other policies.
    EXECUTE format($dml$
        INSERT INTO %I.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct)
        SELECT pz.id, 2, 'Hurto', round(robo.suma_asegurada / 2, 2), 15.00
          FROM %I.poliza pz
          JOIN %I.cobertura robo ON robo.poliza_id = pz.id AND robo.nombre = 'Robo de celular'
         WHERE pz.numero = $1
           AND NOT EXISTS (SELECT 1 FROM %I.cobertura c
                            WHERE c.poliza_id = pz.id AND c.nombre = 'Hurto')
    $dml$, aseg, aseg, aseg, aseg) USING poliza;

    EXECUTE format($dml$
        INSERT INTO %I.policy_coverage (policy_id, coverage_id, display_order, sum_insured, deductible_pct)
        SELECT p.id, $2, 2, round(robo.sum_insured / 2, 2), 15.00
          FROM %I.policy p
          JOIN %I.policy_coverage robo ON robo.policy_id = p.id
          JOIN %I.coverage rc ON rc.id = robo.coverage_id AND rc.name = 'Robo de celular'
         WHERE p.external_policy_number = $1
           AND NOT EXISTS (SELECT 1 FROM %I.policy_coverage pc
                            WHERE pc.policy_id = p.id AND pc.coverage_id = $2)
    $dml$, tenant, tenant, tenant, tenant, tenant) USING poliza, hurto;

    -- The theft case that hung from the robbery coverage, which excludes it.
    EXECUTE format($dml$
        UPDATE %I.cases c
           SET coverage_id = $2
          FROM arbiter_common.claim_cause cc, %I.policy p
         WHERE cc.id = c.claim_cause_id AND cc.name = 'Hurto'
           AND p.id = c.policy_id AND p.external_policy_number = $1
           AND c.coverage_id <> $2
           AND EXISTS (SELECT 1 FROM %I.policy_coverage pc
                        WHERE pc.policy_id = c.policy_id AND pc.coverage_id = $2)
    $dml$, tenant, tenant, tenant) USING poliza, hurto;
END $$;

COMMIT;

-- =============================================================================
-- Optional · align BBVA case 11 with the new fixture
-- =============================================================================
--
-- Claimed amounts are not rewritten here, but case 11 claims 94% of its sum insured under a 50% cap
-- and is marked Fast Track. To match the new seed (240,000, 48%), run by hand:
--
-- UPDATE arbiter_bbva.cases SET claimed_amount = 240000.00 WHERE id = 11;
-- UPDATE arbiter_bbva.rule_result SET evaluated_value = 'ratio=48.0% max=50.0%'
--  WHERE case_id = 11 AND rule_type = 'FT_AMOUNT_RATIO';
-- UPDATE arbiter_bbva.risk_analysis
--    SET risk_score = 0.216, risk_band = 'LOW',
--        risk_breakdown = jsonb_set(jsonb_set(jsonb_set(risk_breakdown,
--            '{0,rawScore}', '0.48'), '{0,weightedContribution}', '0.216'),
--            '{0,rationale}', '"Monto reclamado es 48% de la suma asegurada"')
--  WHERE case_id = 11;

-- =============================================================================
-- Check (afterwards, per schema)
-- =============================================================================
--
-- 1. Each coverage has its FAST_TRACK row with configuration, and none is active without coverage:
--
-- SELECT c.name, r.active, r.configuration->>'maxClaimedAmountRatio' AS tope
--   FROM arbiter_bbva.coverage c
--   LEFT JOIN arbiter_bbva.insurer_rule r
--          ON r.coverage_id = c.id AND r.rule_type = 'FAST_TRACK'
--  ORDER BY c.id;
-- SELECT count(*) FROM arbiter_bbva.insurer_rule
--  WHERE rule_type = 'FAST_TRACK' AND coverage_id IS NULL AND active;  -- 0
--
-- 2. No rule_result left with the old type:
--
-- SELECT rule_type, count(*) FROM arbiter_bbva.rule_result GROUP BY 1 ORDER BY 1;
--
-- 3. Every claim cause of the branch is covered by some coverage:
--
-- WITH excl AS (
--   SELECT r.coverage_id, (jsonb_array_elements(r.configuration->'excludedClaimCauseIds'))::bigint AS cause
--     FROM arbiter_bbva.insurer_rule r WHERE r.rule_type='COVERAGE_EXCLUSION' AND r.active)
-- SELECT cc.name, coalesce(string_agg(cov.name, ', '), '(NINGUNA)') AS cubierto_por
--   FROM arbiter_common.claim_cause cc
--   LEFT JOIN arbiter_bbva.coverage cov ON cov.branch_id = cc.branch_id
--    AND NOT EXISTS (SELECT 1 FROM excl e WHERE e.coverage_id = cov.id AND e.cause = cc.id)
--  WHERE cc.branch_id = 1 GROUP BY cc.id, cc.name ORDER BY cc.id;
--
-- 4. No case hangs from a coverage that excludes its cause (except the two "uncovered theft" demo
--    cases), nor from one its policy doesn't have:
--
-- SELECT c.id, c.claim_cause_id, c.coverage_id
--   FROM arbiter_bbva.cases c JOIN arbiter_bbva.insurer_rule r
--     ON r.rule_type='COVERAGE_EXCLUSION' AND r.active AND r.coverage_id=c.coverage_id
--  WHERE r.configuration->'excludedClaimCauseIds' @> to_jsonb(c.claim_cause_id);
-- SELECT c.id FROM arbiter_bbva.cases c
--  WHERE NOT EXISTS (SELECT 1 FROM arbiter_bbva.policy_coverage pc
--                     WHERE pc.policy_id=c.policy_id AND pc.coverage_id=c.coverage_id);
