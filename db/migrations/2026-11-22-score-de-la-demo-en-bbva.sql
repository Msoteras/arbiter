-- 2026-11-22 · BBVA's scoring tuned for the demo case (Tomás Ibarra, Tecnología Portátil, Rotura
-- accidental): Medium risk when it asks for the missing photo, High once the photo turns out to be
-- published on the internet.
--
-- Weights (sum 100%): the photo found on the internet weighs the most, because it is the signal the
-- demo shows. Cuts: Medium from 15, High from 38, Critical from 75.
--
-- Expected scores (claim 445.000 on a 600.000 sum insured, policy up to date, no fraud record):
--   prior claims     before the photo        after the photo (model: approve / manual / reject)
--   1                24 Medium               42 / 47 / 52 High
--   2                30 Medium               44 / 49 / 54 High   (+12 if the photo matches a practice case)
--   3 or more        35 Medium               47 / 52 / 57 High
-- Every rehearsal leaves one more prior claim: delete the practice cases before the demo.
--
-- Same steps as ScoringConfigurationService.upsert: snapshot to history, then replace weights and
-- cuts. Affects every new or reclassified BBVA case; stored scores stay as they are.

BEGIN;

DO $$
DECLARE
    config_id BIGINT;
    now_ts    TIMESTAMPTZ := now();
BEGIN
    SELECT id INTO config_id FROM arbiter_bbva.scoring_configuration ORDER BY id LIMIT 1;
    IF config_id IS NULL THEN
        RAISE EXCEPTION 'arbiter_bbva: no tiene configuración de score';
    END IF;

    INSERT INTO arbiter_bbva.scoring_configuration_history
        (snapshot_config, valid_from, valid_to, changed_at, reason, changed_by, scoring_configuration_id)
    SELECT jsonb_build_object(
               'id', c.id,
               'bands', (SELECT jsonb_agg(jsonb_build_object('band', b.band,
                                                             'minScoreInclusive', b.min_score_inclusive)
                                          ORDER BY b.min_score_inclusive)
                           FROM arbiter_bbva.score_band b WHERE b.scoring_configuration_id = c.id),
               'factors', (SELECT jsonb_agg(jsonb_build_object('weight', f.weight, 'factorId', f.factor_code)
                                            ORDER BY f.id)
                             FROM arbiter_bbva.factor_weight f WHERE f.scoring_configuration_id = c.id),
               'fullAnalysisOnFastTrack', c.full_analysis_on_fast_track),
           c.valid_from, now_ts, now_ts, 'Score ajustado para la demo',
           (SELECT id FROM arbiter_bbva.insurer_referent ORDER BY id LIMIT 1), c.id
      FROM arbiter_bbva.scoring_configuration c
     WHERE c.id = config_id;

    DELETE FROM arbiter_bbva.factor_weight WHERE scoring_configuration_id = config_id;
    INSERT INTO arbiter_bbva.factor_weight (factor_code, weight, scoring_configuration_id)
    VALUES ('amount_ratio',           0.12, config_id),
           ('claim_frequency',        0.08, config_id),
           ('policy_standing',        0.06, config_id),
           ('image_reuse',            0.12, config_id),
           ('image_web_match',        0.30, config_id),
           ('fraud_history',          0.16, config_id),
           ('document_inconsistency', 0.06, config_id),
           ('llm_recommendation',     0.10, config_id);

    DELETE FROM arbiter_bbva.score_band WHERE scoring_configuration_id = config_id;
    INSERT INTO arbiter_bbva.score_band (band, min_score_inclusive, scoring_configuration_id)
    VALUES ('LOW',      0.00, config_id),
           ('MEDIUM',   0.15, config_id),
           ('HIGH',     0.38, config_id),
           ('CRITICAL', 0.75, config_id);

    UPDATE arbiter_bbva.scoring_configuration SET valid_from = now_ts WHERE id = config_id;

    RAISE NOTICE 'arbiter_bbva: score de la demo aplicado (configuración %)', config_id;
END $$;

COMMIT;
