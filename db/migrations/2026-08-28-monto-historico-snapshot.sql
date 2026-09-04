-- =============================================================================
-- 2026-08-28 · policy_snapshot.total_amount_claimed (Trazabilidad del expediente)
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Agrega <tenant>.policy_snapshot.total_amount_claimed: el monto de los
-- siniestros previos del asegurado, congelado junto al resto de la foto que la
-- BD Aseguradora devolvió al clasificar. classification-service ya lo calcula
-- en cada corrida (`InsuredHistory.totalAmountClaimed`) y lo descartaba.
--
-- NULLABLE a propósito, al revés que `previous_claims`: los snapshots previos a
-- esta columna no tienen manera de saber el monto, y un 0 se leería como "nunca
-- reclamó un peso". Se pueblan solos en la próxima clasificación; no hay
-- backfill posible porque el histórico de la BD Aseguradora ya se movió.
--
-- IMPORTANTE: los servicios corren con ddl-auto=validate. Aplicar ANTES de
-- desplegar el código que declara el campo, o cases-service no levanta.
-- `init-multitenant.sql` ya quedó actualizado para las bases nuevas.
--
-- Idempotente: se puede correr más de una vez sin romper nada.
-- =============================================================================

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format(
            'ALTER TABLE %I.policy_snapshot ADD COLUMN IF NOT EXISTS total_amount_claimed NUMERIC(15,2)',
            tenant);

    END LOOP;
END $$;

COMMIT;

-- Verificación: una fila por aseguradora.
SELECT table_schema, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'policy_snapshot'
   AND column_name = 'total_amount_claimed'
 ORDER BY table_schema;
