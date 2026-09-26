-- 2026-08-21 · Removes the four Provincia policies an old snapshot bug leaked into arbiter_bbva.policy
-- (ids 13-16). No case references them. Railway only; idempotent.

BEGIN;

DELETE FROM arbiter_bbva.policy WHERE id IN (13, 14, 15, 16);

COMMIT;
