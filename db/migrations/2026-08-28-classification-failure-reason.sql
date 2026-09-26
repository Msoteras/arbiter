-- 2026-08-28 · cases.classification_failure_reason/_message: written when classification runs out of
-- retries, read by ClassificationRefreshScheduler to requeue infrastructure failures. Idempotent.

BEGIN;

ALTER TABLE arbiter_bbva.cases
    ADD COLUMN IF NOT EXISTS classification_failure_reason  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS classification_failure_message TEXT;

ALTER TABLE arbiter_provincia.cases
    ADD COLUMN IF NOT EXISTS classification_failure_reason  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS classification_failure_message TEXT;

COMMIT;

-- Check:
-- SELECT column_name, data_type FROM information_schema.columns
--  WHERE table_schema = 'arbiter_bbva' AND table_name = 'cases'
--    AND column_name LIKE 'classification_failure%';
-- SELECT column_name, data_type FROM information_schema.columns
--  WHERE table_schema = 'arbiter_provincia' AND table_name = 'cases'
--    AND column_name LIKE 'classification_failure%';
