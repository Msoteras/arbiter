-- 2026-10-12 · arbiter_common.shedlock: one row per scheduled sweep of cases-service, so that with
-- several instances only one runs each sweep at a time (SchedulingConfig). Has to exist before the
-- cases-service that locks its sweeps is deployed: without it no sweep runs. Idempotent.

CREATE TABLE IF NOT EXISTS arbiter_common.shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
