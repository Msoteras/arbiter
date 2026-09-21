-- =============================================================================
-- 2026-09-20 · Quién actualizó el tope de atribución de cada ramo
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Agrega:
--   · <tenant>.settlement_authority → updated_by, quién tocó el tope por última vez.
--
-- Las filas existentes quedan con NULL: no hay con qué completarlas retroactivamente,
-- y NULL ahí significa exactamente eso — "no se sabe quién la dejó así", no un error.
--
-- IMPORTANTE: los servicios corren con ddl-auto=validate. Aplicar ANTES de
-- desplegar el código que declara el campo, o cases-service no levanta.
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
            'ALTER TABLE %I.settlement_authority
                ADD COLUMN IF NOT EXISTS updated_by BIGINT REFERENCES arbiter_common.users(id)',
            tenant);

    END LOOP;
END $$;

COMMIT;

-- Verificación: la columna nueva, una fila por aseguradora.
SELECT table_schema, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'settlement_authority' AND column_name = 'updated_by'
 ORDER BY table_schema;
