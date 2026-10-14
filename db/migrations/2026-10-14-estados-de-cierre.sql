-- 2026-10-14 · Closing statuses: WITHDRAWN (the insured abandoned the claim), VOIDED (the claim never
-- should have existed) and PRESCRIBED (rejected under art. 58, not on its merits). Terminal: only the
-- existing reopening takes them out. The insured sees "Cerrado" for the first two and "Rechazado" for
-- the third. Apply before deploying the code (ddl-auto=validate on the enum). Idempotent.

BEGIN;

INSERT INTO arbiter_common.case_status (id, name, description, insured_status, insured_status_code, is_final) VALUES
    (10, 'WITHDRAWN',  'El asegurado desistió del reclamo',                 'Cerrado',   'CLOSED',   TRUE),
    (11, 'VOIDED',     'Denuncia dada de baja por error de carga',          'Cerrado',   'CLOSED',   TRUE),
    (12, 'PRESCRIBED', 'Rechazado por prescripción (art. 58, Ley 17.418)', 'Rechazado', 'REJECTED', TRUE)
ON CONFLICT (name) DO NOTHING;

SELECT setval(pg_get_serial_sequence('arbiter_common.case_status', 'id'),
              (SELECT MAX(id) FROM arbiter_common.case_status));

COMMIT;

-- Verification: 12 rows, the last three final.
-- SELECT id, name, insured_status, insured_status_code, is_final FROM arbiter_common.case_status ORDER BY id;
