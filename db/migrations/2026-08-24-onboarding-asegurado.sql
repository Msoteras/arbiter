-- 2026-08-24 · Onboarding columns and versioned image consent on arbiter_*.insured.
-- Without them auth, cases and classification fail schema validation. Idempotent.

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

-- Check: the 4 new columns in both tenants.
SELECT table_schema, column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'insured'
   AND column_name IN ('image_consent_version','image_consent_at',
                        'onboarding_complete','onboarding_completed_at')
 ORDER BY table_schema, column_name;
