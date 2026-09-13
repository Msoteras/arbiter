-- =============================================================================
-- 2026-09-13 · Peritaje visible para el asegurado como "En verificación"
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- PENDING_EXPERT_REPORT le mostraba 'En análisis' al asegurado, pero su seguimiento
-- ya listaba "Enviado a verificación con un perito": el título y el badge
-- contradecían al timeline. La derivación se nombra; el motivo, nunca.
--
-- `init-multitenant.sql` ya quedó actualizado para las bases nuevas.
-- Idempotente: se puede correr más de una vez sin romper nada.
-- =============================================================================

BEGIN;

UPDATE arbiter_common.case_status
   SET insured_status = 'En verificación'
 WHERE name = 'PENDING_EXPERT_REPORT';

COMMIT;

SELECT name, insured_status FROM arbiter_common.case_status WHERE name = 'PENDING_EXPERT_REPORT';
