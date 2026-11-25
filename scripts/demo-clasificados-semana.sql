-- =============================================================================
-- Demo 25/11 — fills "Clasificados en la semana" on the engine board (BBVA).
--
-- The board lists the cases whose last classification run finished in the last seven
-- calendar days (EngineBoardService.CLASSIFIED_DAYS). On 25/11 that window starts on
-- 19/11 00:00 ART; most BBVA runs finished earlier, so the pile would show two cards.
--
-- This moves each run (every step of the case, so its duration stays the same) to finish
-- on a day between 19/11 and 24/11. Only classification_step changes: it is progress for
-- the board, not audit, and nothing else reads it (llm_analysis, rule_result and
-- case_classification stay as they are).
--
-- Usage:  psql "$DATABASE_URL" -f scripts/demo-clasificados-semana.sql
-- Undo:   the block at the end, commented out, puts every run back on its original day.
-- =============================================================================

BEGIN;

-- Target finish time of each case's run, in UTC (ART + 3 h). Ordered by day so the 12 the
-- board shows mix the five results.
CREATE TEMP TABLE demo_target (case_id BIGINT PRIMARY KEY, finish TIMESTAMPTZ NOT NULL) ON COMMIT DROP;
INSERT INTO demo_target VALUES
    (113, '2026-11-24 17:10:00+00'),  -- Recomienda aprobar
    (108, '2026-11-24 14:55:00+00'),  -- No recomienda aprobar
    (117, '2026-11-24 12:40:00+00'),  -- Fast Track
    ( 65, '2026-11-23 18:40:00+00'),  -- No recomienda aprobar
    (112, '2026-11-23 15:50:00+00'),  -- Falta documentación
    (110, '2026-11-23 13:30:00+00'),  -- Revisión manual
    (116, '2026-11-22 19:30:00+00'),  -- No recomienda aprobar
    (114, '2026-11-22 16:00:00+00'),  -- Recomienda aprobar
    ( 56, '2026-11-22 13:15:00+00'),  -- Fast Track
    (109, '2026-11-21 20:10:00+00'),  -- Recomienda aprobar
    (107, '2026-11-21 17:44:36+00'),  -- Fast Track (already here)
    (111, '2026-11-21 14:20:00+00'),  -- Revisión manual
    (115, '2026-11-20 21:15:00+00'),  -- Recomienda aprobar
    (106, '2026-11-20 19:00:00+00'),  -- Revisión manual
    (118, '2026-11-20 16:30:00+00'),  -- Recomienda aprobar
    ( 66, '2026-11-20 13:40:00+00'),  -- No recomienda aprobar
    (120, '2026-11-19 20:30:00+00'),  -- Recomienda aprobar
    (105, '2026-11-19 17:44:36+00'),  -- Recomienda aprobar (already here)
    (119, '2026-11-19 14:10:00+00');  -- No recomienda aprobar

-- Each case has a single run, so its last step is the run's end.
UPDATE arbiter_bbva.classification_step s
SET occurred_at = s.occurred_at + (t.finish - last.finish)
FROM demo_target t
JOIN (SELECT case_id, max(occurred_at) AS finish
      FROM arbiter_bbva.classification_step
      GROUP BY case_id) last ON last.case_id = t.case_id
WHERE s.case_id = t.case_id;

SELECT case_id, max(occurred_at) AS finish
FROM arbiter_bbva.classification_step
WHERE stage IN ('FINISHED', 'FAILED')
GROUP BY case_id
ORDER BY finish DESC;

COMMIT;

-- =============================================================================
-- Undo: original finish time of each run, as it was on 24/11 before this script.
-- =============================================================================
-- BEGIN;
-- CREATE TEMP TABLE demo_target (case_id BIGINT PRIMARY KEY, finish TIMESTAMPTZ NOT NULL) ON COMMIT DROP;
-- INSERT INTO demo_target VALUES
--     ( 56, '2026-11-18 09:05:33.103441+00'),
--     ( 65, '2026-11-16 06:47:28.561593+00'),
--     ( 66, '2026-11-16 06:47:43.820372+00'),
--     (105, '2026-11-19 17:44:36+00'),
--     (106, '2026-10-26 17:44:36+00'),
--     (107, '2026-11-21 17:44:36+00'),
--     (108, '2026-11-13 17:44:36+00'),
--     (109, '2026-11-09 17:44:36+00'),
--     (110, '2026-11-10 17:44:36+00'),
--     (111, '2026-11-06 17:44:36+00'),
--     (112, '2026-11-16 17:44:36+00'),
--     (113, '2026-11-08 17:44:36+00'),
--     (114, '2026-11-03 17:44:36+00'),
--     (115, '2026-11-13 17:44:36+00'),
--     (116, '2026-11-11 17:44:36+00'),
--     (117, '2026-11-17 17:44:36+00'),
--     (118, '2026-11-02 17:44:36+00'),
--     (119, '2026-10-29 17:44:36+00'),
--     (120, '2026-11-18 17:44:36+00');
-- UPDATE arbiter_bbva.classification_step s
-- SET occurred_at = s.occurred_at + (t.finish - last.finish)
-- FROM demo_target t
-- JOIN (SELECT case_id, max(occurred_at) AS finish
--       FROM arbiter_bbva.classification_step
--       GROUP BY case_id) last ON last.case_id = t.case_id
-- WHERE s.case_id = t.case_id;
-- COMMIT;
