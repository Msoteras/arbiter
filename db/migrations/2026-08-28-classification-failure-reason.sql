-- Migración manual, una vez, contra la BD viva (Railway). No hay Flyway: db/init-multitenant.sql
-- solo corre al crear el volumen desde cero, así que estas dos columnas nuevas de `cases`
-- (classification_failure_reason, classification_failure_message — ver el CREATE TABLE en
-- db/init-multitenant.sql) no llegan solas a una base que ya tiene datos.
--
-- Las escribe classification-service (CaseOutcomeRepository.recordClassificationFailure) cuando
-- processClaimClassification agota los reintentos, y las lee cases-service para decidir qué
-- expedientes CLASSIFICATION_FAILED reencolar (ClassificationRefreshScheduler.recoverInfrastructureFailures).
--
-- IF NOT EXISTS: para poder re-correr esto sin romper si alguien ya lo aplicó a mano.

BEGIN;

ALTER TABLE arbiter_bbva.cases
    ADD COLUMN IF NOT EXISTS classification_failure_reason  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS classification_failure_message TEXT;

ALTER TABLE arbiter_provincia.cases
    ADD COLUMN IF NOT EXISTS classification_failure_reason  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS classification_failure_message TEXT;

COMMIT;

-- Verificación:
-- SELECT column_name, data_type FROM information_schema.columns
--  WHERE table_schema = 'arbiter_bbva' AND table_name = 'cases'
--    AND column_name LIKE 'classification_failure%';
-- SELECT column_name, data_type FROM information_schema.columns
--  WHERE table_schema = 'arbiter_provincia' AND table_name = 'cases'
--    AND column_name LIKE 'classification_failure%';
