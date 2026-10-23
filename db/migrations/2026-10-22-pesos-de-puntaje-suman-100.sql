-- 2026-10-22 · Scoring weights add up to 100%: rules-service rejects a configuration whose factors don't
-- sum exactly 1, in whole percents above 0. Rescales each configuration in proportion (weight / total),
-- rounded to whole percents by largest remainder, and drops the factors at 0. Each rescaled
-- configuration leaves a row in scoring_configuration_history, like a save from the screen.
-- Idempotent. Safe before or after deploying the code; before, no referente sees the old totals.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
    cfg    RECORD;
    zeroed BIGINT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        FOR cfg IN EXECUTE format($sql$
            SELECT sc.id,
                   count(fw.id) AS factors,
                   COALESCE(sum(fw.weight), 0) AS total,
                   COALESCE(bool_or(fw.weight <= 0), FALSE) AS has_zero,
                   COALESCE(bool_or(fw.weight * 100 <> trunc(fw.weight * 100)), FALSE) AS has_fraction
              FROM %1$I.scoring_configuration sc
              LEFT JOIN %1$I.factor_weight fw ON fw.scoring_configuration_id = sc.id
             GROUP BY sc.id
             ORDER BY sc.id
        $sql$, tenant) LOOP
            IF cfg.factors = 0 OR (cfg.total = 1 AND NOT cfg.has_zero AND NOT cfg.has_fraction) THEN
                RAISE NOTICE '%: la configuración % ya suma 100%%', tenant, cfg.id;
                CONTINUE;
            END IF;

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
                       sc.valid_from, now(), now(), 'Pesos llevados a porcentajes que suman 100%%', NULL, sc.id
                  FROM %1$I.scoring_configuration sc
                 WHERE sc.id = $1
            $sql$, tenant) USING cfg.id;

            EXECUTE format('DELETE FROM %I.factor_weight WHERE scoring_configuration_id = $1 AND weight <= 0', tenant)
                USING cfg.id;

            EXECUTE format($sql$
                WITH shares AS (
                    SELECT id, weight * 100 / sum(weight) OVER () AS exact
                      FROM %1$I.factor_weight
                     WHERE scoring_configuration_id = $1),
                ranked AS (
                    SELECT id,
                           floor(exact) AS whole,
                           row_number() OVER (ORDER BY exact - floor(exact) DESC, id) AS position,
                           100 - sum(floor(exact)) OVER () AS leftover
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

            EXECUTE format('UPDATE %I.scoring_configuration SET valid_from = now() WHERE id = $1', tenant)
                USING cfg.id;

            RAISE NOTICE '%: la configuración % sumaba % y ahora suma 100%%',
                tenant, cfg.id, round(cfg.total * 100) || '%';
        END LOOP;
    END LOOP;
END $$;

COMMIT;
