-- =============================================================================
-- 2026-08-25 · arbiter_common.users.email — índice único
--
-- Por qué: el alta masiva de asegurados ("Dar de alta usuarios") reconoce a la
-- persona POR EMAIL para no duplicarla — alguien asegurado en dos compañías es un
-- solo login con dos filas en user_insurer (Roman Castillo, users(9), es
-- exactamente ese caso). Hoy ese de-dup vive solo en código: `users.email` no
-- tiene UNIQUE, cosa que el propio esquema marca como pendiente:
--
--   "Worth revisiting: auth0_sub and email are both login keys, and without a
--    constraint two rows can claim the same identity."
--
-- Sin el índice, dos corridas simultáneas (o un bug) meten identidades duplicadas
-- en silencio y quedan dos filas peleando por el mismo login.
--
-- NO destructiva, pero SÍ puede fallar: si ya hay emails repetidos, el índice no
-- se crea. Por eso el chequeo va primero — si devuelve filas, hay que resolver
-- esos duplicados a mano antes de correr el ALTER.
-- =============================================================================

-- ─── Paso 1: ¿hay duplicados? Si esto devuelve filas, NO sigas. ──────────────
SELECT lower(email) AS email, COUNT(*) AS veces,
       array_agg(id ORDER BY id) AS ids
  FROM arbiter_common.users
 GROUP BY lower(email)
HAVING COUNT(*) > 1;

-- ─── Paso 2: el índice ───────────────────────────────────────────────────────
-- Sobre lower(email): el login busca por email y 'Ana@x.com' y 'ana@x.com' son la
-- misma casilla — un UNIQUE sensible a mayúsculas dejaría pasar justo el duplicado
-- que esto viene a impedir.
BEGIN;

CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower_uq
    ON arbiter_common.users (lower(email));

COMMIT;

-- Verificación.
SELECT indexname, indexdef
  FROM pg_indexes
 WHERE schemaname = 'arbiter_common'
   AND tablename = 'users'
   AND indexname = 'users_email_lower_uq';
