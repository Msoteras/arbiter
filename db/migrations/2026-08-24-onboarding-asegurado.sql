-- =============================================================================
-- 2026-08-24 · arbiter_*.insured — columnas de onboarding + consentimiento versionado
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Por qué: commit 90e3579 (onboarding de asegurado) suma 4 columnas a `insured`
-- para el flujo de primer ingreso (InsuredProfileController/Service):
--   · image_consent_version, image_consent_at: versión y fecha del consentimiento
--     de uso de imagen, antes solo existía el booleano `image_consent`.
--   · onboarding_complete, onboarding_completed_at: si el asegurado ya pasó la
--     pantalla de bienvenida.
--
-- `init-multitenant.sql` ya quedó actualizado: una base creada de cero desde ese
-- script ya trae las columnas. Este archivo es solo para las bases que ya existían
-- (Railway) — con ddl-auto=validate, sin esto auth-service/cases-service/
-- classification-service no arrancan (Hibernate: "Schema-validation: missing column").
--
-- Idempotente: se puede correr más de una vez sin romper nada.
-- =============================================================================

BEGIN;

ALTER TABLE arbiter_bbva.insured
    ADD COLUMN IF NOT EXISTS image_consent_version   VARCHAR(20),
    ADD COLUMN IF NOT EXISTS image_consent_at         TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS onboarding_complete      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS onboarding_completed_at  TIMESTAMPTZ;

ALTER TABLE arbiter_provincia.insured
    ADD COLUMN IF NOT EXISTS image_consent_version   VARCHAR(20),
    ADD COLUMN IF NOT EXISTS image_consent_at         TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS onboarding_complete      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS onboarding_completed_at  TIMESTAMPTZ;

COMMIT;

-- Verificación: las 4 columnas nuevas en ambos tenants.
SELECT table_schema, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'insured'
   AND column_name IN ('image_consent_version','image_consent_at',
                        'onboarding_complete','onboarding_completed_at')
 ORDER BY table_schema, column_name;
