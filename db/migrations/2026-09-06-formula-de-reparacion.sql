-- 2026-09-06 · Repair formula: coverage.settlement_formula (TOTAL_LOSS or REPAIR) and the formula applied
-- on case_settlement. A repair keeps the policy alive, so pending installments are not deducted and the
-- quote is the base. Apply before deploying the code (ddl-auto=validate).

BEGIN;

-- 1 · coverage.settlement_formula, on the coverage because the catalog already splits loss from damage.
-- TOTAL_LOSS keeps the current behavior.
ALTER TABLE arbiter_bbva.coverage
    ADD COLUMN IF NOT EXISTS settlement_formula VARCHAR(20) NOT NULL DEFAULT 'TOTAL_LOSS';
ALTER TABLE arbiter_provincia.coverage
    ADD COLUMN IF NOT EXISTS settlement_formula VARCHAR(20) NOT NULL DEFAULT 'TOTAL_LOSS';

ALTER TABLE arbiter_bbva.coverage
    DROP CONSTRAINT IF EXISTS coverage_settlement_formula_check;
ALTER TABLE arbiter_bbva.coverage
    ADD CONSTRAINT coverage_settlement_formula_check
        CHECK (settlement_formula IN ('TOTAL_LOSS', 'REPAIR'));

ALTER TABLE arbiter_provincia.coverage
    DROP CONSTRAINT IF EXISTS coverage_settlement_formula_check;
ALTER TABLE arbiter_provincia.coverage
    ADD CONSTRAINT coverage_settlement_formula_check
        CHECK (settlement_formula IN ('TOTAL_LOSS', 'REPAIR'));

-- Damage coverages repair; matched by name, which is what says what happened to the item.
UPDATE arbiter_bbva.coverage      SET settlement_formula = 'REPAIR' WHERE name = 'Daño accidental';
UPDATE arbiter_provincia.coverage SET settlement_formula = 'REPAIR' WHERE name = 'Daño accidental';

-- 2 · case_settlement: the formula applied
ALTER TABLE arbiter_bbva.case_settlement
    DROP CONSTRAINT IF EXISTS case_settlement_formula_check;
ALTER TABLE arbiter_bbva.case_settlement
    ADD CONSTRAINT case_settlement_formula_check
        CHECK (formula IN ('TOTAL_LOSS', 'REPAIR'));

ALTER TABLE arbiter_provincia.case_settlement
    DROP CONSTRAINT IF EXISTS case_settlement_formula_check;
ALTER TABLE arbiter_provincia.case_settlement
    ADD CONSTRAINT case_settlement_formula_check
        CHECK (formula IN ('TOTAL_LOSS', 'REPAIR'));

-- Signed settlements on a damage coverage are relabeled: the amount already was a repair.
UPDATE arbiter_bbva.case_settlement s
   SET formula = 'REPAIR'
  FROM arbiter_bbva.coverage c
 WHERE c.id = s.coverage_id AND c.settlement_formula = 'REPAIR' AND s.formula = 'TOTAL_LOSS';

UPDATE arbiter_provincia.case_settlement s
   SET formula = 'REPAIR'
  FROM arbiter_provincia.coverage c
 WHERE c.id = s.coverage_id AND c.settlement_formula = 'REPAIR' AND s.formula = 'TOTAL_LOSS';

COMMIT;

-- Check:
-- SELECT name, settlement_formula, settlement_basis FROM arbiter_provincia.coverage ORDER BY id;
-- SELECT case_id, formula, settled_amount FROM arbiter_provincia.case_settlement;
