-- =============================================================================
-- 2026-08-30 · rule_result acepta las reglas de alcance de cobertura
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Dos reglas duras (D9) se evaluaban sin dejar rastro auditable:
--   · covers_family_group     — si la cobertura alcanza al grupo familiar
--   · claim_exhausts_coverage — si un siniestro liquidado previo ya la consumió
--
-- Las dos bloquean el Fast Track y explican su motivo en pantalla, pero no
-- llegaban a `rule_result`, así que el analista no las veía en la lista de
-- reglas evaluadas y la Disposición SSN 2/2023 quedaba a medias.
--
-- No entraban por dos razones de esquema, y esto arregla las dos:
--
--   1. `rule_id` era NOT NULL con FK a `insurer_rule`. Estas dos reglas NO son
--      filas de esa tabla: son columnas de `coverage`, que el referente edita en
--      la solapa de Coberturas. No hay id al que apuntar. Ser auditable y ser
--      una fila de la tabla de reglas son dos cosas distintas.
--
--   2. `rule_type` era VARCHAR(20) y CLAIM_EXHAUSTS_COVERAGE mide 23.
--
-- IMPORTANTE: los servicios corren con ddl-auto=validate. Aplicar ANTES de
-- desplegar el código, o classification-service no levanta.
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
            'ALTER TABLE %I.rule_result ALTER COLUMN rule_id DROP NOT NULL', tenant);

        EXECUTE format(
            'ALTER TABLE %I.rule_result ALTER COLUMN rule_type TYPE VARCHAR(40)', tenant);

    END LOOP;
END $$;

COMMIT;

-- Verificación: dos filas por aseguradora, rule_id nullable y rule_type en 40.
SELECT table_schema, column_name, data_type, character_maximum_length, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'rule_result'
   AND column_name IN ('rule_id', 'rule_type')
 ORDER BY table_schema, column_name;
