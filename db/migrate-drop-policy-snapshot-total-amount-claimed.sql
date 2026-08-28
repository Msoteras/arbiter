-- Migración manual, una vez, contra la BD viva (Railway). No hay Flyway: db/init-multitenant.sql
-- solo corre al crear el volumen desde cero, así que un DROP acordado no llega solo a una base
-- que ya tiene datos.
--
-- `policy_snapshot.total_amount_claimed` es drift: nunca estuvo en db/init-multitenant.sql (lo
-- detectó scripts/check-schema-consistency.py, 28/08), y verificado que ningún código la lee ni
-- la escribe — ni la entidad JPA (cases-service/.../PolicySnapshot.java no la mapea) ni ningún SQL
-- del repo.
--
-- El monto total reclamado histórico SÍ existe como concepto, pero es del siniestro, no de la
-- póliza: `InsurerDatabaseAdapter.getHistory()` lo calcula al vuelo sumando
-- aseguradora_*.siniestro_historico.monto_indemnizado, que ya está completo en
-- db/init-multitenant.sql y con datos realistas en db/seed-demo.sql. Esta columna era una versión
-- mal ubicada de lo mismo, sin dueño.

BEGIN;

ALTER TABLE arbiter_bbva.policy_snapshot      DROP COLUMN IF EXISTS total_amount_claimed;
ALTER TABLE arbiter_provincia.policy_snapshot DROP COLUMN IF EXISTS total_amount_claimed;

COMMIT;

-- Verificación:
-- SELECT column_name FROM information_schema.columns
--  WHERE table_name = 'policy_snapshot' AND column_name = 'total_amount_claimed';
-- (0 filas en ambos esquemas)
