-- 2026-09-13 · PENDING_EXPERT_REPORT shows the insured 'En verificación', matching their timeline. The
-- referral is named, the reason never. Idempotent.

BEGIN;

UPDATE arbiter_common.case_status
   SET insured_status = 'En verificación'
 WHERE name = 'PENDING_EXPERT_REPORT';

COMMIT;

SELECT name, insured_status FROM arbiter_common.case_status WHERE name = 'PENDING_EXPERT_REPORT';
