-- 2026-08-31 · LAPSED status (id 8): no documents from the insured 18 months after filing (BBVA
-- NSIN001 §9). Apply before deploying the code (ddl-auto=validate). Idempotent.

BEGIN;

INSERT INTO arbiter_common.case_status (id, name, description, insured_status, is_final) VALUES
    (8, 'LAPSED', 'Caducado por 18 meses de inacción del asegurado', 'Caducado', TRUE)
ON CONFLICT (name) DO NOTHING;

SELECT setval(pg_get_serial_sequence('arbiter_common.case_status', 'id'),
              (SELECT MAX(id) FROM arbiter_common.case_status));

COMMIT;
