-- 2026-11-05 · The risk score adds the model's recommendation: the llm_recommendation factor at 20%.
-- The other factors keep their proportions in the remaining 80%, in whole percents by largest
-- remainder, because rules-service rejects a configuration that doesn't sum 100%. Each changed
-- configuration leaves a row in scoring_configuration_history, like a save from the screen.
-- A configuration with no factors stays as it is: its insurer hasn't configured the score.
-- Idempotent. Safe before or after deploying the code: without an evaluator the factor is skipped and
-- the score normalizes by the weights that did run. Deploy the frontend too, or the referente's screen
-- counts a 20% that it doesn't list.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
    cfg    RECORD;
    zeroed BIGINT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        FOR cfg IN EXECUTE format($sql$
            SELECT sc.id
              FROM %1$I.scoring_configuration sc
             WHERE EXISTS (SELECT 1 FROM %1$I.factor_weight fw WHERE fw.scoring_configuration_id = sc.id)
               AND NOT EXISTS (SELECT 1 FROM %1$I.factor_weight fw
                                WHERE fw.scoring_configuration_id = sc.id
                                  AND fw.factor_code = 'llm_recommendation')
             ORDER BY sc.id
        $sql$, tenant) LOOP
            EXECUTE format($sql$
                INSERT INTO %1$I.scoring_configuration_history
                    (snapshot_config, valid_from, valid_to, changed_at, reason, changed_by, scoring_configuration_id)
                SELECT jsonb_build_object(
                           'id', sc.id,
                           'fullAnalysisOnFastTrack', sc.full_analysis_on_fast_track,
                           'factors', COALESCE((
                               SELECT jsonb_agg(jsonb_build_object('factorId', fw.factor_code, 'weight', fw.weight)
                                                ORDER BY fw.id)
                                 FROM %1$I.factor_weight fw
                                WHERE fw.scoring_configuration_id = sc.id), '[]'::jsonb),
                           'bands', COALESCE((
                               SELECT jsonb_agg(jsonb_build_object('band', sb.band,
                                                                   'minScoreInclusive', sb.min_score_inclusive)
                                                ORDER BY sb.id)
                                 FROM %1$I.score_band sb
                                WHERE sb.scoring_configuration_id = sc.id), '[]'::jsonb)),
                       sc.valid_from, now(), now(), 'Se suma la recomendación del modelo con peso 20%%', NULL, sc.id
                  FROM %1$I.scoring_configuration sc
                 WHERE sc.id = $1
            $sql$, tenant) USING cfg.id;

            EXECUTE format($sql$
                WITH shares AS (
                    SELECT id, weight * 80 / sum(weight) OVER () AS exact
                      FROM %1$I.factor_weight
                     WHERE scoring_configuration_id = $1),
                ranked AS (
                    SELECT id,
                           floor(exact) AS whole,
                           row_number() OVER (ORDER BY exact - floor(exact) DESC, id) AS position,
                           80 - sum(floor(exact)) OVER () AS leftover
                      FROM shares)
                UPDATE %1$I.factor_weight fw
                   SET weight = (r.whole + CASE WHEN r.position <= r.leftover THEN 1 ELSE 0 END) / 100
                  FROM ranked r
                 WHERE fw.id = r.id
            $sql$, tenant) USING cfg.id;

            EXECUTE format('SELECT count(*) FROM %I.factor_weight WHERE scoring_configuration_id = $1 AND weight <= 0',
                tenant) INTO zeroed USING cfg.id;
            IF zeroed > 0 THEN
                RAISE EXCEPTION '%: en la configuración % un factor queda en 0%% al redondear. Corregí los pesos a mano.',
                    tenant, cfg.id;
            END IF;

            EXECUTE format($sql$
                INSERT INTO %1$I.factor_weight (factor_code, weight, scoring_configuration_id)
                VALUES ('llm_recommendation', 0.20, $1)
            $sql$, tenant) USING cfg.id;

            EXECUTE format('UPDATE %I.scoring_configuration SET valid_from = now() WHERE id = $1', tenant)
                USING cfg.id;

            RAISE NOTICE '%: la configuración % suma la recomendación del modelo con peso 20%%', tenant, cfg.id;
        END LOOP;
    END LOOP;
END $$;

COMMIT;
