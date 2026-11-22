-- 2026-11-22 · Removes the rehearsal of the demo case from arbiter_bbva: case 71 (Tomás Ibarra,
-- notebook breakage, rejected on 21/11) and the fraud record declared on it. Left in place, the
-- next run of the demo would count case 71 as a prior claim, match its photo as reused, and add
-- the fraud record to the score before the photo is even asked for.
--
-- Same order as 2026-11-11-limpieza-de-expedientes-de-prueba.sql for the tables with no cascade.
-- The stored metrics of 21/11 go too: they counted this case, and reports-service recalculates the
-- day the next time a report asks for it. Data only; running it twice changes nothing.

BEGIN;

DO $$
DECLARE
    snapshot_id BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM arbiter_bbva.cases WHERE id = 71) THEN
        RAISE NOTICE 'arbiter_bbva: el expediente 71 ya no existe';
        RETURN;
    END IF;

    SELECT policy_snapshot_id INTO snapshot_id FROM arbiter_bbva.cases WHERE id = 71;

    DELETE FROM arbiter_bbva.insured_fraud_record WHERE case_id = 71;

    CREATE TEMP TABLE doomed_classification ON COMMIT DROP AS
        SELECT classification_id AS id FROM arbiter_bbva.cases
         WHERE id = 71 AND classification_id IS NOT NULL
        UNION
        SELECT cc.id FROM arbiter_bbva.case_classification cc
          JOIN arbiter_bbva.llm_analysis la ON la.id = cc.llm_analysis_id
         WHERE la.case_id = 71;

    UPDATE arbiter_bbva.cases SET classification_id = NULL
     WHERE id = 71 OR classification_id IN (SELECT id FROM doomed_classification);
    DELETE FROM arbiter_bbva.case_classification WHERE id IN (SELECT id FROM doomed_classification);
    DELETE FROM arbiter_bbva.case_settlement WHERE case_id = 71;
    UPDATE arbiter_bbva.image_analysis SET similar_document_id = NULL
     WHERE similar_document_id IN (SELECT id FROM arbiter_bbva.case_documents WHERE case_id = 71);
    UPDATE arbiter_bbva.case_referral SET report_document_id = NULL
     WHERE report_document_id IN (SELECT id FROM arbiter_bbva.case_documents WHERE case_id = 71);

    DELETE FROM arbiter_bbva.cases WHERE id = 71;

    DELETE FROM arbiter_bbva.policy_snapshot ps
     WHERE ps.id = snapshot_id
       AND NOT EXISTS (SELECT 1 FROM arbiter_bbva.cases c WHERE c.policy_snapshot_id = ps.id)
       AND NOT EXISTS (SELECT 1 FROM arbiter_bbva.case_settlement s WHERE s.policy_snapshot_id = ps.id);

    DELETE FROM arbiter_bbva.metrics_day WHERE day = DATE '2026-11-21';

    RAISE NOTICE 'arbiter_bbva: expediente 71 y su antecedente de fraude borrados';
END $$;

COMMIT;
