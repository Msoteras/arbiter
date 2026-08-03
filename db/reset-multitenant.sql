-- =============================================================================
-- Arbiter — DESTRUCTIVE reset of the multi-tenant database
--
-- ⚠️  BORRA TODOS LOS DATOS. Está pensado para entornos de desarrollo y para la
--     base de Railway mientras el esquema todavía se mueve. NUNCA correrlo contra
--     algo que tenga datos reales.
--
-- Por qué existe: `init-multitenant.sql` es sólo CREATE, sin DROP — está escrito
-- para una base vacía y falla ruidosamente si no lo está. Cuando el esquema cambia
-- (columnas nuevas en `cases`, `insured.image_consent`, `case_status_history.actor`,
-- `image_analysis.model`, …), re-correrlo sobre una base ya poblada no funciona.
-- Este script deja la base como recién creada para poder volver a correrlo.
--
-- Uso completo (los tres pasos, en orden):
--
--   psql "$DATABASE_PUBLIC_URL" -f db/reset-multitenant.sql
--   psql "$DATABASE_PUBLIC_URL" -f db/init-multitenant.sql
--   psql "$DATABASE_PUBLIC_URL" -f db/seed-demo.sql
--
-- En Railway usá DATABASE_PUBLIC_URL, no DATABASE_URL: ese último apunta a
-- *.railway.internal y sólo resuelve dentro de la red de Railway.
--
-- La extensión `vector` NO se toca: vive en `public`, es un objeto de la base y
-- volver a crearla suele requerir superusuario. init-multitenant.sql ya la crea
-- con IF NOT EXISTS.
-- =============================================================================

BEGIN;

-- Descubre los esquemas en vez de listarlos a mano: los de tenant se crean
-- dinámicamente desde `insurer` (hoy bbva y provincia, mañana los que se sumen),
-- así que una lista fija quedaría desactualizada en cuanto se onboardee otra
-- aseguradora. CASCADE porque hay FKs entre esquemas (cases → arbiter_common).
DO $$
DECLARE
    schema_name TEXT;
BEGIN
    FOR schema_name IN
        SELECT nspname
          FROM pg_namespace
         WHERE nspname = 'arbiter_common'
            OR nspname LIKE 'arbiter\_%'      -- arbiter_bbva, arbiter_provincia, …
            OR nspname LIKE 'aseguradora\_%'  -- BD Aseguradora simulada por tenant
    LOOP
        RAISE NOTICE 'Dropping schema %', schema_name;
        EXECUTE format('DROP SCHEMA IF EXISTS %I CASCADE', schema_name);
    END LOOP;
END $$;

COMMIT;

-- Verificación: no debería quedar ninguna fila.
SELECT nspname AS esquemas_que_quedaron
  FROM pg_namespace
 WHERE nspname = 'arbiter_common'
    OR nspname LIKE 'arbiter\_%'
    OR nspname LIKE 'aseguradora\_%';
