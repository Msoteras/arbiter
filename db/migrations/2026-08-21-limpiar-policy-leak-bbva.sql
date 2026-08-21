-- =============================================================================
-- 2026-08-21 · Limpiar el leak de pólizas de Provincia en arbiter_bbva.policy
--
-- Migración puntual y NO destructiva para el resto del esquema — borra 4 filas
-- huérfanas, nada más. Aplicar sobre Railway, no sobre una base recién inicializada
-- (ahí este leak no existe).
--
-- Qué borra: arbiter_bbva.policy ids 13, 14, 15 y 16. Son las que
-- PolicyTenantLocator.java documenta como el bug del 16/8 — pólizas de Provincia
-- (POL-CEL-2026-905, POL-TEC-2026-311, POL-CEL-2026-777, POL-CEL-2026-501) que un
-- snapshot local viejo escribió mal en el esquema de BBVA. El bug de origen ya está
-- arreglado (el código pregunta primero a la aseguradora, no al primer snapshot local
-- que responda); esto solo saca la basura que dejó mientras estuvo activo.
--
-- Verificado antes de escribir esto (no de memoria):
--   · Las 4 son exactamente las que aparecen en arbiter_bbva.policy pero NO en
--     aseguradora_bbva.poliza — es decir, números que no le pertenecen a esta compañía.
--   · cases_policy_id_fkey es la ÚNICA FK que apunta a arbiter_bbva.policy, y ningún
--     `arbiter_bbva.cases.policy_id` referencia ninguna de las 4 (0 filas). No hay
--     expediente real colgando de esto.
--   · El lado espejo (arbiter_provincia.policy) no tiene ninguna póliza de BBVA
--     filtrada — el leak fue de un solo sentido.
--
-- Ids 11 y 12 no se tocan porque no existen (huecos de secuencia, no basura).
--
-- Idempotente: un DELETE por id ya borrado no hace nada, se puede correr más de una vez.
-- =============================================================================

BEGIN;

DELETE FROM arbiter_bbva.policy WHERE id IN (13, 14, 15, 16);

COMMIT;
