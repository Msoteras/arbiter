-- 2026-10-11 · case_status.insured_status_code: the code the insured's API carries instead of the
-- internal status name. insured_status stays as its label, and from now on it is what the portal
-- shows; PENDING_EXPERT_REPORT's says the case is with an expert, which the insured may know.
-- Idempotent.
--
-- Run it BEFORE deploying: every module that maps CaseState validates the column at startup.

BEGIN;

ALTER TABLE arbiter_common.case_status ADD COLUMN IF NOT EXISTS insured_status_code VARCHAR(40);

UPDATE arbiter_common.case_status
   SET insured_status_code = CASE name
       WHEN 'PENDING_CLASSIFICATION' THEN 'UNDER_ANALYSIS'
       WHEN 'PENDING_ANALYST_REVIEW' THEN 'UNDER_ANALYSIS'
       WHEN 'CLASSIFICATION_FAILED'  THEN 'UNDER_ANALYSIS'
       WHEN 'AWAITING_DOCUMENTATION' THEN 'AWAITING_DOCUMENTATION'
       WHEN 'PENDING_EXPERT_REPORT'  THEN 'UNDER_VERIFICATION'
       WHEN 'PENDING_REPAIR'         THEN 'UNDER_REPAIR'
       WHEN 'APPROVED'               THEN 'APPROVED'
       WHEN 'REJECTED'               THEN 'REJECTED'
       WHEN 'LAPSED'                 THEN 'LAPSED'
   END;

UPDATE arbiter_common.case_status
   SET insured_status = 'En peritaje'
 WHERE name = 'PENDING_EXPERT_REPORT';

-- Fails here, inside the transaction, if a status was left without a code.
ALTER TABLE arbiter_common.case_status ALTER COLUMN insured_status_code SET NOT NULL;

COMMIT;

SELECT name, insured_status, insured_status_code FROM arbiter_common.case_status ORDER BY id;
