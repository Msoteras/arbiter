-- =============================================================================
-- 2026-08-25 · Martina Soteras — mail de contacto para notificaciones
--
-- Migración puntual y NO destructiva, para aplicar sobre una base con datos
-- (Railway) sin pasar por el trío reset → init → seed.
--
-- Qué cambia: `insured.email` de Martina (42.987.654) pasa de
-- mocciafederico@hotmail.com a aylusandu@gmail.com, en los dos tenants donde es
-- clienta. Es la dirección a la que SendGrid le manda los avisos de cambio de
-- estado de sus expedientes — tiene 21, así que es la que más notificaciones
-- genera en una demo.
--
-- Qué NO cambia, a propósito:
--
--   · `arbiter_common.users(1).email` — es su CREDENCIAL de login, no un dato de
--     contacto. El login valida contra Auth0 por email (AUTH_PROVIDER=auth0), así
--     que cambiarlo acá sin cambiarlo allá la dejaría afuera.
--
--   · `aseguradora_bbva.asegurado.email` — es el registro de la COMPAÑÍA, no el
--     nuestro. Que difieran es el modelo funcionando, no un desajuste: el
--     asegurado actualiza su contacto en Arbiter (onboarding / Mi perfil) sin
--     tocar lo que la aseguradora tiene archivado.
--
-- Efecto sobre el alta masiva: ninguno. Martina sigue saliendo en `omitidos`
-- porque el directorio de BBVA la lista con un mail que en la plataforma es la
-- cuenta de un analista (usuario 7), mientras su perfil cuelga del usuario 1 —
-- ver InsuredProfileConflictException. Resolver eso implica decidir cuál de las
-- dos identidades vale, y no es una decisión que corresponda a este archivo.
-- Su login y sus 21 expedientes no se ven afectados.
--
-- Idempotente.
-- =============================================================================

BEGIN;

UPDATE arbiter_bbva.insured
   SET email = 'aylusandu@gmail.com'
 WHERE dni = '42.987.654';

UPDATE arbiter_provincia.insured
   SET email = 'aylusandu@gmail.com'
 WHERE dni = '42.987.654';

COMMIT;

-- Verificación.
SELECT 'bbva' AS tenant, dni, name, surname, email FROM arbiter_bbva.insured WHERE dni = '42.987.654'
UNION ALL
SELECT 'provincia', dni, name, surname, email FROM arbiter_provincia.insured WHERE dni = '42.987.654';
