-- 2026-09-11 · Deletes duplicate status transitions left by concurrent sweeps (now prevented by
-- CaseStatusService.transitionIfStillIn). A duplicate has the same case, statuses, reason, actor and
-- changed_by, less than 60 s after an identical row; the first one stays. SYSTEM rows only: legitimate
-- repeats are minutes apart. Written from the code, not the data: run the inspection first. Idempotent.

-- ─── INSPECTION: run this FIRST and read the output ──────────────────────────
-- Everything the DELETE below would remove, plus people's rows that match but are kept (se_borra).
--
-- SELECT h.case_id,
--        h.id,
--        h.changed_at,
--        h.actor,
--        i.name AS desde,
--        f.name AS hasta,
--        h.reason,
--        (h.actor = 'SYSTEM') AS se_borra
--   FROM arbiter_bbva.case_status_history h
--   LEFT JOIN arbiter_common.case_status i ON i.id = h.initial_status_id
--   JOIN arbiter_common.case_status f ON f.id = h.final_status_id
--  WHERE EXISTS (SELECT 1
--                  FROM arbiter_bbva.case_status_history keep
--                 WHERE keep.case_id = h.case_id
--                   AND keep.initial_status_id IS NOT DISTINCT FROM h.initial_status_id
--                   AND keep.final_status_id = h.final_status_id
--                   AND keep.reason = h.reason
--                   AND keep.actor = h.actor
--                   AND keep.changed_by IS NOT DISTINCT FROM h.changed_by
--                   AND (keep.changed_at, keep.id) < (h.changed_at, h.id)
--                   AND h.changed_at - keep.changed_at <= INTERVAL '60 seconds')
--  ORDER BY h.case_id, h.changed_at;
--
-- Same for arbiter_provincia (replace both arbiter_bbva).

BEGIN;

DELETE FROM arbiter_bbva.case_status_history h
 WHERE h.actor = 'SYSTEM'
   AND EXISTS (SELECT 1
                 FROM arbiter_bbva.case_status_history keep
                WHERE keep.case_id = h.case_id
                  AND keep.initial_status_id IS NOT DISTINCT FROM h.initial_status_id
                  AND keep.final_status_id = h.final_status_id
                  AND keep.reason = h.reason
                  AND keep.actor = h.actor
                  AND keep.changed_by IS NOT DISTINCT FROM h.changed_by
                  -- The earlier row stays; (changed_at, id) breaks ties on the same timestamp.
                  AND (keep.changed_at, keep.id) < (h.changed_at, h.id)
                  AND h.changed_at - keep.changed_at <= INTERVAL '60 seconds');

DELETE FROM arbiter_provincia.case_status_history h
 WHERE h.actor = 'SYSTEM'
   AND EXISTS (SELECT 1
                 FROM arbiter_provincia.case_status_history keep
                WHERE keep.case_id = h.case_id
                  AND keep.initial_status_id IS NOT DISTINCT FROM h.initial_status_id
                  AND keep.final_status_id = h.final_status_id
                  AND keep.reason = h.reason
                  AND keep.actor = h.actor
                  AND keep.changed_by IS NOT DISTINCT FROM h.changed_by
                  AND (keep.changed_at, keep.id) < (h.changed_at, h.id)
                  AND h.changed_at - keep.changed_at <= INTERVAL '60 seconds');

COMMIT;

-- Check: both queries must return 0 rows after applying.
--
-- SELECT COUNT(*) FROM arbiter_bbva.case_status_history h
--  WHERE h.actor = 'SYSTEM'
--    AND EXISTS (SELECT 1 FROM arbiter_bbva.case_status_history keep
--                 WHERE keep.case_id = h.case_id
--                   AND keep.initial_status_id IS NOT DISTINCT FROM h.initial_status_id
--                   AND keep.final_status_id = h.final_status_id
--                   AND keep.reason = h.reason
--                   AND keep.actor = h.actor
--                   AND keep.changed_by IS NOT DISTINCT FROM h.changed_by
--                   AND (keep.changed_at, keep.id) < (h.changed_at, h.id)
--                   AND h.changed_at - keep.changed_at <= INTERVAL '60 seconds');
--
-- Same for arbiter_provincia.
--
-- And the originally reported case, to look at it:
-- SELECT h.id, h.changed_at, h.actor, i.name AS desde, f.name AS hasta, h.reason
--   FROM arbiter_bbva.case_status_history h
--   LEFT JOIN arbiter_common.case_status i ON i.id = h.initial_status_id
--   JOIN arbiter_common.case_status f ON f.id = h.final_status_id
--  WHERE h.case_id = :case_id
--  ORDER BY h.changed_at;
