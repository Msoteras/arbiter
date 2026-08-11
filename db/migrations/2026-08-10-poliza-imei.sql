-- =============================================================================
-- 2026-08-10 · aseguradora_*.poliza.imei
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Por qué: el cruce "el IMEI del documento no coincide con el del bien asegurado"
-- (D4b) no tenía contra qué comparar — el IMEI no existía en el modelo, solo
-- aparecía hardcodeado dentro del string `bien_asegurado` en mocks y tests.
--
-- `init-multitenant.sql` y `seed-demo.sql` ya quedaron actualizados: una base
-- creada de cero desde esos scripts ya trae la columna y los valores. Este
-- archivo es solo para las bases que ya existían.
--
-- Idempotente: se puede correr más de una vez sin romper nada.
-- =============================================================================

BEGIN;

ALTER TABLE aseguradora_bbva.poliza      ADD COLUMN IF NOT EXISTS imei VARCHAR(20);
ALTER TABLE aseguradora_provincia.poliza ADD COLUMN IF NOT EXISTS imei VARCHAR(20);

-- Solo ramo Celulares: una notebook no tiene IMEI, ahí la columna queda NULL.
-- Los valores son los mismos que sembró seed-demo.sql, para que una base migrada
-- y una creada de cero queden idénticas.
UPDATE aseguradora_bbva.poliza SET imei = '351000000000042' WHERE numero = 'POL-CEL-2026-042';
UPDATE aseguradora_bbva.poliza SET imei = '353000000000099' WHERE numero = 'POL-CEL-2025-099';
UPDATE aseguradora_bbva.poliza SET imei = '354000000000054' WHERE numero = '2030405';
UPDATE aseguradora_bbva.poliza SET imei = '355000000000024' WHERE numero = 'POL-CEL-2026-118';
UPDATE aseguradora_bbva.poliza SET imei = '356000000000015' WHERE numero = 'POL-CEL-2026-205';

UPDATE aseguradora_provincia.poliza SET imei = '357000000000023' WHERE numero = 'POL-CEL-2026-501';
UPDATE aseguradora_provincia.poliza SET imei = '358000000000056' WHERE numero = 'POL-CEL-2026-777';

COMMIT;

-- Verificación: las 7 pólizas de Celulares con IMEI, la de Tecnología Portátil en NULL.
SELECT 'bbva' AS schema, numero, rama, imei FROM aseguradora_bbva.poliza
UNION ALL
SELECT 'provincia', numero, rama, imei FROM aseguradora_provincia.poliza
ORDER BY schema, numero;
