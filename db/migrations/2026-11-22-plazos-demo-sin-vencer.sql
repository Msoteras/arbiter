-- 2026-11-22 · Demo data: no case shows "Plazo vencido".
-- The seed cases were filed around 10/10, so their art. 56 term ran out on 09/11–10/11.
-- Every case whose term is running and already past due gets a fresh 30-day term from today,
-- the same reset CaseStatusService applies when a paused term resumes.
-- Running term = neither paused (CaseStatus.pausingTheTerm) nor closed (CaseStatus.terminal).
-- Idempotent: once moved, a case is no longer past due.

BEGIN;

DO $$
DECLARE
    s       text;
    r       record;
    total   int := 0;
BEGIN
    FOR s IN SELECT schema_name FROM arbiter_common.insurer ORDER BY 1 LOOP
        FOR r IN EXECUTE format($q$
            UPDATE %I.cases c
               SET response_deadline = CURRENT_DATE + 30,
                   updated_at        = NOW()
              FROM arbiter_common.case_status cs
             WHERE cs.id = c.current_status_id
               AND c.response_deadline < CURRENT_DATE
               AND cs.name NOT IN ('AWAITING_DOCUMENTATION', 'PENDING_EXPERT_REPORT', 'PENDING_REPAIR',
                                   'APPROVED', 'REJECTED', 'LAPSED', 'WITHDRAWN', 'VOIDED', 'PRESCRIBED')
         RETURNING c.id, c.response_deadline$q$, s) LOOP
            RAISE NOTICE '% · expediente % · nuevo plazo %', s, r.id, r.response_deadline;
            total := total + 1;
        END LOOP;
    END LOOP;
    RAISE NOTICE 'Expedientes con plazo renovado: %', total;
END $$;

COMMIT;
