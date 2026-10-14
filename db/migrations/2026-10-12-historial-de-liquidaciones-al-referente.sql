-- 2026-10-12 · Backfills the case history with the settlements that went to the referent before
-- cases-service started recording it: the case stays in PENDING_ANALYST_REVIEW meanwhile, so the
-- trail never said why it was waiting.
--   · One row when the analyst's amount went over the branch ceiling (at confirmed_at).
--   · One more when the referent authorized or returned it (at authorized_at), the return with its
--     reason as the observation.
-- Same from == to rows and the same wording CaseServiceImpl writes from now on. Only the last round
-- of each settlement can be rebuilt: re-confirming after a return overwrites the row.
-- Data only. Idempotent: a case that already has the row is skipped.

BEGIN;

DO $$
DECLARE
    tenant      TEXT;
    review_id   BIGINT;
    sent        BIGINT;
    answered    BIGINT;
BEGIN
    SELECT id INTO STRICT review_id FROM arbiter_common.case_status WHERE name = 'PENDING_ANALYST_REVIEW';

    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        EXECUTE format($sql$
            INSERT INTO %1$I.case_status_history
                   (case_id, initial_status_id, final_status_id, actor, reason, changed_at)
            SELECT s.case_id, $1, $1, 'ANALYST',
                   'liquidación de $' || chr(160)
                       || translate(to_char(s.settled_amount, 'FM999,999,999,990.00'), ',.', '.,')
                       || ' enviada al referente: supera la atribución del ramo ($' || chr(160)
                       || translate(to_char(s.authority_limit, 'FM999,999,999,990.00'), ',.', '.,')
                       || ')',
                   s.confirmed_at
              FROM %1$I.case_settlement s
             WHERE (s.status IN ('PENDING_AUTHORIZATION', 'RETURNED') OR s.authorized_by_user_id IS NOT NULL)
               AND s.authority_limit IS NOT NULL
               AND NOT EXISTS (
                       SELECT 1 FROM %1$I.case_status_history h
                        WHERE h.case_id = s.case_id AND h.reason LIKE 'liquidación de %% enviada al referente%%')
        $sql$, tenant) USING review_id;
        GET DIAGNOSTICS sent = ROW_COUNT;

        EXECUTE format($sql$
            INSERT INTO %1$I.case_status_history
                   (case_id, initial_status_id, final_status_id, actor, reason, observation, changed_at)
            SELECT s.case_id, $1, $1, 'REFERENT',
                   CASE s.status WHEN 'RETURNED' THEN 'liquidación devuelta por el referente'
                                 ELSE 'liquidación autorizada por el referente' END,
                   CASE s.status WHEN 'RETURNED' THEN s.return_reason END,
                   s.authorized_at
              FROM %1$I.case_settlement s
             WHERE s.status IN ('AUTHORIZED', 'RETURNED')
               AND s.authorized_by_user_id IS NOT NULL
               AND s.authorized_at IS NOT NULL
               AND NOT EXISTS (
                       SELECT 1 FROM %1$I.case_status_history h
                        WHERE h.case_id = s.case_id
                          AND h.reason IN ('liquidación devuelta por el referente',
                                           'liquidación autorizada por el referente'))
        $sql$, tenant) USING review_id;
        GET DIAGNOSTICS answered = ROW_COUNT;

        RAISE NOTICE '%: % envíos al referente y % respuestas agregados al historial', tenant, sent, answered;
    END LOOP;
END $$;

COMMIT;
