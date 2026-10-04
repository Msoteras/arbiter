-- 2026-09-06 · expert_assessment.indemnifiable_amount (NSIN001 §2.6), typed in by the analyst from the
-- report. Nullable: a confirmed fraud pays nothing, and 0 would read as a conclusion. Apply before deploying.

BEGIN;

ALTER TABLE arbiter_bbva.expert_assessment
    ADD COLUMN IF NOT EXISTS indemnifiable_amount NUMERIC(15,2);
ALTER TABLE arbiter_provincia.expert_assessment
    ADD COLUMN IF NOT EXISTS indemnifiable_amount NUMERIC(15,2);

COMMIT;

-- Check:
-- SELECT case_id, verdict, indemnifiable_amount FROM arbiter_bbva.expert_assessment;
