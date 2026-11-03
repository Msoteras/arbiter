-- 2026-10-23 · Provincia's Tecnología Portátil gets the Robo and Hurto coverages seed-demo.sql already
-- loads, so every claim cause of the branch is answered by exactly one coverage. Until now a laptop
-- robbery or theft had no coverage, and PolicyResyncScheduler skipped the insurer database's
-- "Robo de celular" on that branch every night. Celulares needs nothing: its accidental damage
-- coverage was created from the referent panel.
--   · Robo (external name "Robo de celular", as the insurer database calls it) excludes Rotura
--     accidental, Hurto and Caída; Hurto excludes Rotura accidental, Robo en vía pública and Caída.
--   · Each one gets its hard rules and its Fast Track, as in seed-demo.sql.
-- Claim causes are looked up by name: their ids differ between this database and the seed. The rules
-- are attributed to the insurer's first referent, as 2026-09-28 did. Text literals use U&'...'
-- escapes because a PowerShell pipe once turned an accent into '?'.
-- Idempotent: a coverage that already exists is left as is, rules included. Data only; safe before or
-- after any deploy.

BEGIN;

DO $$
DECLARE
    laptop_branch  BIGINT;
    breakage_id    BIGINT;
    robbery_id     BIGINT;
    theft_id       BIGINT;
    drop_id        BIGINT;
    referent       BIGINT;
    coverage_id    BIGINT;
BEGIN
    SELECT id INTO laptop_branch FROM arbiter_provincia.branch
     WHERE external_name = U&'Tecnolog\00EDa Port\00E1til';
    IF laptop_branch IS NULL THEN
        RAISE NOTICE 'arbiter_provincia: no tiene el ramo Tecnología Portátil, no hay nada que hacer';
        RETURN;
    END IF;

    SELECT id INTO breakage_id FROM arbiter_provincia.claim_cause
     WHERE branch_id = laptop_branch AND name = 'Rotura accidental';
    SELECT id INTO robbery_id FROM arbiter_provincia.claim_cause
     WHERE branch_id = laptop_branch AND name = U&'Robo en v\00EDa p\00FAblica';
    SELECT id INTO theft_id FROM arbiter_provincia.claim_cause
     WHERE branch_id = laptop_branch AND name = 'Hurto';
    SELECT id INTO drop_id FROM arbiter_provincia.claim_cause
     WHERE branch_id = laptop_branch AND name = U&'Ca\00EDda';
    IF breakage_id IS NULL OR robbery_id IS NULL OR theft_id IS NULL OR drop_id IS NULL THEN
        RAISE EXCEPTION 'arbiter_provincia: falta algún hecho generador de Tecnología Portátil';
    END IF;

    SELECT user_id INTO referent FROM arbiter_provincia.insurer_referent ORDER BY id LIMIT 1;
    IF referent IS NULL THEN
        RAISE EXCEPTION 'arbiter_provincia: no tiene referente al que atribuirle las reglas';
    END IF;

    -- ─── Robo ────────────────────────────────────────────────────────────────────
    IF EXISTS (SELECT 1 FROM arbiter_provincia.coverage
                WHERE branch_id = laptop_branch AND external_name = 'Robo de celular') THEN
        RAISE NOTICE 'arbiter_provincia: Robo ya existe en Tecnología Portátil';
    ELSE
        INSERT INTO arbiter_provincia.coverage (name, external_name, description, report_deadline_hours,
                                                max_events_per_year, deductible, waiting_period_days,
                                                branch_id, settlement_formula, settlement_basis,
                                                deductible_basis, second_event_percentage,
                                                deduct_pending_installments, deduct_overdue_balance)
        VALUES ('Robo', 'Robo de celular', U&'Cobertura por robo de equipo port\00E1til', 96, 2, 10.00, 30,
                laptop_branch, 'TOTAL_LOSS', 'LESSER_OF_SUM_AND_REPLACEMENT', 'LOSS_AMOUNT', 50.00,
                TRUE, TRUE)
        RETURNING id INTO coverage_id;

        INSERT INTO arbiter_provincia.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                                    blocks_fast_track, branch_id, coverage_id,
                                                    configuration, created_by)
        VALUES
            (TRUE, '2026-01-01 00:00:00+00',
             U&'La cobertura de robo solo cubre robo en v\00EDa p\00FAblica', 'COVERAGE_EXCLUSION',
             'RECHAZAR', 1, TRUE, laptop_branch, coverage_id,
             jsonb_build_object('excludedClaimCauseIds', jsonb_build_array(breakage_id, theft_id, drop_id)),
             referent),
            (TRUE, '2026-01-01 00:00:00+00', 'Carencia de la cobertura (cobertura ' || coverage_id || ')',
             'WAITING_PERIOD', 'DERIVAR', 2, TRUE, laptop_branch, coverage_id, '{}', referent),
            (TRUE, '2026-01-01 00:00:00+00',
             'Plazo de denuncia a la aseguradora (cobertura ' || coverage_id || ')',
             'REPORT_DEADLINE', 'DERIVAR', 3, TRUE, laptop_branch, coverage_id, '{}', referent),
            (FALSE, '2026-01-01 00:00:00+00',
             'Plazo de la denuncia policial (cobertura ' || coverage_id || ')',
             'POLICE_DEADLINE', 'DERIVAR', 4, TRUE, laptop_branch, coverage_id, '{"deadlineHours":72}',
             referent),
            (TRUE, '2026-01-01 00:00:00+00', U&'Tope de eventos por a\00F1o (cobertura ' || coverage_id || ')',
             'MAX_EVENTS_YEAR', 'DERIVAR', 5, TRUE, laptop_branch, coverage_id, '{}', referent),
            (TRUE, '2026-01-01 00:00:00+00', U&'Fast Track \2014 Robo', 'FAST_TRACK', 'APROBAR', 1, FALSE,
             laptop_branch, coverage_id,
             jsonb_build_object(
                 'maxClaimedAmountRatio', 0.5, 'maxPriorClaims', 0, 'requiresUpToDatePolicy', TRUE,
                 'requiredDocumentTypes', jsonb_build_array('police_report', 'purchase_proof'),
                 'criteria', jsonb_build_array(
                     'Primer siniestro del asegurado',
                     'Monto reclamado inferior al 50% de la suma asegurada',
                     U&'P\00F3liza al d\00EDa con sus pagos')),
             referent);

        RAISE NOTICE 'arbiter_provincia: Robo creada en Tecnología Portátil (id %)', coverage_id;
    END IF;

    -- ─── Hurto ───────────────────────────────────────────────────────────────────
    IF EXISTS (SELECT 1 FROM arbiter_provincia.coverage
                WHERE branch_id = laptop_branch AND external_name = 'Hurto') THEN
        RAISE NOTICE 'arbiter_provincia: Hurto ya existe en Tecnología Portátil';
    ELSE
        -- One event a year, so no second-event percentage.
        INSERT INTO arbiter_provincia.coverage (name, external_name, description, report_deadline_hours,
                                                max_events_per_year, deductible, waiting_period_days,
                                                branch_id, settlement_formula, settlement_basis,
                                                deductible_basis, second_event_percentage,
                                                deduct_pending_installments, deduct_overdue_balance)
        VALUES ('Hurto', 'Hurto', U&'Cobertura por hurto de equipo port\00E1til', 96, 1, 10.00, 30,
                laptop_branch, 'TOTAL_LOSS', 'LESSER_OF_SUM_AND_REPLACEMENT', 'LOSS_AMOUNT', NULL,
                TRUE, TRUE)
        RETURNING id INTO coverage_id;

        INSERT INTO arbiter_provincia.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                                    blocks_fast_track, branch_id, coverage_id,
                                                    configuration, created_by)
        VALUES
            (TRUE, '2026-01-01 00:00:00+00', 'La cobertura de hurto solo cubre hurto', 'COVERAGE_EXCLUSION',
             'RECHAZAR', 1, TRUE, laptop_branch, coverage_id,
             jsonb_build_object('excludedClaimCauseIds', jsonb_build_array(breakage_id, robbery_id, drop_id)),
             referent),
            (TRUE, '2026-01-01 00:00:00+00', 'Carencia de la cobertura (cobertura ' || coverage_id || ')',
             'WAITING_PERIOD', 'DERIVAR', 2, TRUE, laptop_branch, coverage_id, '{}', referent),
            (TRUE, '2026-01-01 00:00:00+00',
             'Plazo de denuncia a la aseguradora (cobertura ' || coverage_id || ')',
             'REPORT_DEADLINE', 'DERIVAR', 3, TRUE, laptop_branch, coverage_id, '{}', referent),
            (FALSE, '2026-01-01 00:00:00+00',
             'Plazo de la denuncia policial (cobertura ' || coverage_id || ')',
             'POLICE_DEADLINE', 'DERIVAR', 4, TRUE, laptop_branch, coverage_id, '{"deadlineHours":72}',
             referent),
            (TRUE, '2026-01-01 00:00:00+00', U&'Tope de eventos por a\00F1o (cobertura ' || coverage_id || ')',
             'MAX_EVENTS_YEAR', 'DERIVAR', 5, TRUE, laptop_branch, coverage_id, '{}', referent),
            (TRUE, '2026-01-01 00:00:00+00', U&'Fast Track \2014 Hurto', 'FAST_TRACK', 'APROBAR', 2, FALSE,
             laptop_branch, coverage_id,
             jsonb_build_object(
                 'maxClaimedAmountRatio', 0.3, 'maxPriorClaims', 0, 'requiresUpToDatePolicy', TRUE,
                 'requiredDocumentTypes', jsonb_build_array('police_report', 'purchase_proof'),
                 'criteria', jsonb_build_array(
                     'Primer siniestro del asegurado',
                     'Monto reclamado inferior al 30% de la suma asegurada',
                     U&'P\00F3liza al d\00EDa con sus pagos')),
             referent);

        RAISE NOTICE 'arbiter_provincia: Hurto creada en Tecnología Portátil (id %)', coverage_id;
    END IF;
END $$;

COMMIT;
