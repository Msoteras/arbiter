-- =============================================================================
-- 2026-08-31 · Caducidad por inacción del asegurado
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Qué agrega:
--   · arbiter_common.case_status: el estado LAPSED (id 8).
--
-- Por qué: el procedimiento real de gestión de siniestros (doc BBVA
-- Siniestros_NSIN001, §9) marca la "inacción del asegurado ante requerimientos"
-- (18 meses desde la denuncia sin movimiento) como causal de caducidad, distinta
-- de la prescripción legal (1 año desde el hecho) y de un rechazo del analista.
-- LapseSweepScheduler cierra a este estado los expedientes que quedaron
-- esperando documentación del asegurado (AWAITING_DOCUMENTATION) sin que la
-- trajera en ese plazo.
--
-- IMPORTANTE: los servicios corren con ddl-auto=validate. Aplicar esto ANTES
-- de desplegar el código que agrega CaseStatus.LAPSED.
--
-- `init-multitenant.sql` ya quedó actualizado: una base creada de cero desde
-- ese script ya trae este estado. Este archivo es solo para las bases que ya
-- existían.
--
-- Idempotente: se puede correr más de una vez sin romper nada.
-- =============================================================================

BEGIN;

INSERT INTO arbiter_common.case_status (id, name, description, insured_status, is_final) VALUES
    (8, 'LAPSED', 'Caducado por 18 meses de inacción del asegurado', 'Caducado', TRUE)
ON CONFLICT (name) DO NOTHING;

SELECT setval(pg_get_serial_sequence('arbiter_common.case_status', 'id'),
              (SELECT MAX(id) FROM arbiter_common.case_status));

COMMIT;
