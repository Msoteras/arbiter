-- 2026-09-29 · A classification says who decided it. cases.was_fast_track becomes
-- cases.rules_classification: what the rules engine recommended when it settled the case without
-- the model, NULL when the model decided. Fast Track was one of four such outcomes; a coverage
-- exclusion, prescription (art. 58) and missing documentation were saved in llm_analysis with the
-- configured model and prompt, as if the model had recommended them. From now on they write no
-- llm_analysis row, and why the engine decided is in rule_result.
--
-- Backfill of cases classified before this change, from their latest llm_analysis row. None of it
-- is a guess:
--   · FALTA_DOCUMENTACION: the model can't return it (ClaimClassifierImpl.ALLOWED_FROM_MODEL).
--   · Coverage exclusion and prescription: their reason is a fixed text built in code
--     (CoverageRuleEvaluator.excludedReasons, prescriptionResponse). The exclusion had a second
--     wording from 13/08 to 01/09, while coverage was an allow list ("— no está en la lista de
--     hechos generadores cubiertos configurada por la aseguradora"); both end the same way.
-- Their llm_analysis rows stay: the audit trail is append-only. Cases still classifying are left
-- alone, since their latest row belongs to the previous run.
--
-- Two steps, so the running services never see a schema they can't handle:
--   1. This file, before deploying: it only adds and fills the new column. The deployed code keeps
--      writing was_fast_track and ignores rules_classification.
--   2. After deploying classification-, cases- and reports-service, run this file AGAIN (it catches
--      up whatever the old code classified in between) and then
--      2026-09-29-borrar-was-fast-track.sql.
-- Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        EXECUTE format('ALTER TABLE %I.cases ADD COLUMN IF NOT EXISTS rules_classification VARCHAR(50)',
                       tenant);
        EXECUTE format('ALTER TABLE %I.cases DROP CONSTRAINT IF EXISTS cases_rules_classification_check',
                       tenant);
        EXECUTE format(
            'ALTER TABLE %I.cases ADD CONSTRAINT cases_rules_classification_check CHECK (
                rules_classification IS NULL
                OR rules_classification IN (''FAST_TRACK'', ''FALTA_DOCUMENTACION'',
                                            ''LLM_SOLICITA_REVISION_MANUAL'', ''LLM_NO_RECOMIENDA_APROBAR''))',
            tenant);

        IF EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = tenant AND table_name = 'cases'
                      AND column_name = 'was_fast_track') THEN
            -- On the re-run, the new code no longer clears was_fast_track: a case it reclassified
            -- through the model still says TRUE. A model run since the case last entered
            -- classification means the Fast Track is stale.
            EXECUTE format($sql$
                UPDATE %1$I.cases c SET rules_classification = 'FAST_TRACK'
                 WHERE c.was_fast_track AND c.rules_classification IS NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM %1$I.llm_analysis l
                        WHERE l.case_id = c.id
                          AND l.analyzed_at > (
                              SELECT max(h.changed_at) FROM %1$I.case_status_history h
                               WHERE h.case_id = c.id
                                 AND h.final_status_id = (SELECT id FROM arbiter_common.case_status
                                                           WHERE name = 'PENDING_CLASSIFICATION')))
            $sql$, tenant);
        END IF;

        EXECUTE format($sql$
            WITH latest AS (
                SELECT DISTINCT ON (case_id) id, case_id, recommendation
                  FROM %1$I.llm_analysis
                 ORDER BY case_id, id DESC
            )
            UPDATE %1$I.cases c
               SET rules_classification = l.recommendation
              FROM latest l
             WHERE l.case_id = c.id
               AND c.rules_classification IS NULL
               AND c.current_status_id <> (SELECT id FROM arbiter_common.case_status
                                            WHERE name = 'PENDING_CLASSIFICATION')
               AND (l.recommendation = 'FALTA_DOCUMENTACION'
                    OR (l.recommendation = 'LLM_SOLICITA_REVISION_MANUAL'
                        AND EXISTS (SELECT 1 FROM %1$I.llm_reason r
                                     WHERE r.analysis_id = l.id
                                       AND r.reason LIKE 'La cobertura no cubre el hecho generador declarado (%%) — %%configurada por la aseguradora'))
                    OR (l.recommendation = 'LLM_NO_RECOMIENDA_APROBAR'
                        AND EXISTS (SELECT 1 FROM %1$I.llm_reason r
                                     WHERE r.analysis_id = l.id
                                       AND r.reason LIKE '%%prescripto (art. 58, Ley 17.418)%%')))
        $sql$, tenant);
    END LOOP;
END $$;

COMMIT;

-- Check:
-- SELECT rules_classification, count(*) FROM arbiter_bbva.cases GROUP BY 1 ORDER BY 1;
-- SELECT rules_classification, count(*) FROM arbiter_provincia.cases GROUP BY 1 ORDER BY 1;
