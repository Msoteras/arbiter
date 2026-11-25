-- =============================================================================
-- Demo 25/11 — fills "Liquidaciones pendientes" for the BBVA referent.
--
-- Two cases in PENDING_ANALYST_REVIEW get the settlement their analyst confirmed above the
-- branch cap, as SettlementService.confirm leaves it: the row in PENDING_AUTHORIZATION with the
-- held justification, and the history note. The case doesn't move: it waits for the referent.
--
-- Confirmed on 20/11, when the Celulares cap was still $500.000 (it went up to $2.000.000 on
-- 21/11 19:48 UTC), so the frozen limit matches the cap in force at the time.
--
-- Amounts follow SettlementCalculator (TOTAL_LOSS, lesser of sum insured and replacement value,
-- deductible 10% of the sum insured, pending instalments up to the policy's end):
--   105  Lucía Benítez · Robo   1.050.000 − 140.000 − 3 × 28.000 = 826.000
--   106  Diego Ramírez · Hurto    780.000 −  80.000 − 4 × 32.000 = 572.000
--
-- Both cases already have their model run, so the referent's authorization forwards the decision
-- to classification-service and approves the case like any other.
--
-- Usage:  psql "$DATABASE_URL" -f scripts/demo-liquidaciones-al-referente.sql
-- Undo:   the block at the end, commented out.
-- =============================================================================

BEGIN;

INSERT INTO arbiter_bbva.case_settlement
    (case_id, formula, sum_insured, settlement_basis, replacement_value, deductible_rate,
     deductible_basis, event_ordinal, event_percentage, pending_installments, installment_amount,
     deductible_amount, pending_installments_amount, overdue_balance_amount,
     calculated_amount, settled_amount, adjustment_reason,
     status, authority_limit, pending_justification,
     coverage_id, policy_snapshot_id, analyst_id, calculated_at, confirmed_at) VALUES
    (105, 'TOTAL_LOSS', 1400000.00, 'LESSER_OF_SUM_AND_REPLACEMENT', 1050000.00, 10.00,
     'SUM_INSURED', 1, 100.00, 3, 28000.00,
     140000.00, 84000.00, 0.00,
     826000.00, 826000.00, NULL,
     'PENDING_AUTHORIZATION', 500000.00,
     'Robo acreditado con la denuncia policial y la factura del equipo.',
     1, 93, 1, '2026-11-20 15:08:00+00', '2026-11-20 15:10:00+00'),
    (106, 'TOTAL_LOSS', 800000.00, 'LESSER_OF_SUM_AND_REPLACEMENT', 780000.00, 10.00,
     'SUM_INSURED', 1, 100.00, 4, 32000.00,
     80000.00, 128000.00, 0.00,
     572000.00, 572000.00, NULL,
     'PENDING_AUTHORIZATION', 500000.00,
     'Revisé el relato y la documentación: el hurto es consistente con la denuncia.',
     2, 94, 1, '2026-11-20 18:22:00+00', '2026-11-20 18:25:00+00');

-- The note CaseServiceImpl.decide writes, by the assigned analyst (Lucas Gómez, user 2).
INSERT INTO arbiter_bbva.case_status_history
    (reason, observation, changed_at, actor, changed_by, initial_status_id, final_status_id, case_id) VALUES
    ('liquidación de $ 826.000 enviada al referente: supera la atribución del ramo ($ 500.000)',
     NULL, '2026-11-20 15:10:00+00', 'ANALYST', 2, 2, 2, 105),
    ('liquidación de $ 572.000 enviada al referente: supera la atribución del ramo ($ 500.000)',
     NULL, '2026-11-20 18:25:00+00', 'ANALYST', 2, 2, 2, 106);

UPDATE arbiter_bbva.cases SET updated_at = '2026-11-20 15:10:00+00' WHERE id = 105;
UPDATE arbiter_bbva.cases SET updated_at = '2026-11-20 18:25:00+00' WHERE id = 106;

SELECT case_id, status, settled_amount, authority_limit, confirmed_at
FROM arbiter_bbva.case_settlement
WHERE status = 'PENDING_AUTHORIZATION'
ORDER BY confirmed_at;

COMMIT;

-- =============================================================================
-- Undo: only while the referent hasn't acted on them. Once authorized, the cases are APPROVED
-- and the decision is in case_classification; that is not undone here.
-- =============================================================================
-- BEGIN;
-- DELETE FROM arbiter_bbva.case_settlement
--  WHERE case_id IN (105, 106) AND status = 'PENDING_AUTHORIZATION';
-- DELETE FROM arbiter_bbva.case_status_history
--  WHERE case_id IN (105, 106) AND reason LIKE 'liquidación de % enviada al referente%';
-- UPDATE arbiter_bbva.cases SET updated_at = '2026-11-19 20:42:00+00' WHERE id = 105;
-- UPDATE arbiter_bbva.cases SET updated_at = '2026-10-26 20:42:00+00' WHERE id = 106;
-- COMMIT;
