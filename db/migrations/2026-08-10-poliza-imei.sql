-- 2026-08-10 · IMEI column on the insurer policies (aseguradora_*.poliza.imei).
-- For databases created before it; init-multitenant.sql and seed-demo.sql already have it. Idempotent.

BEGIN;

ALTER TABLE aseguradora_bbva.poliza      ADD COLUMN IF NOT EXISTS imei VARCHAR(20);
ALTER TABLE aseguradora_provincia.poliza ADD COLUMN IF NOT EXISTS imei VARCHAR(20);

-- Celulares only: a laptop has no IMEI. Same values as seed-demo.sql.
UPDATE aseguradora_bbva.poliza SET imei = '351000000000042' WHERE numero = 'POL-CEL-2026-042';
UPDATE aseguradora_bbva.poliza SET imei = '353000000000099' WHERE numero = 'POL-CEL-2025-099';
UPDATE aseguradora_bbva.poliza SET imei = '354000000000054' WHERE numero = '2030405';
UPDATE aseguradora_bbva.poliza SET imei = '355000000000024' WHERE numero = 'POL-CEL-2026-118';
UPDATE aseguradora_bbva.poliza SET imei = '356000000000015' WHERE numero = 'POL-CEL-2026-205';

UPDATE aseguradora_provincia.poliza SET imei = '357000000000023' WHERE numero = 'POL-CEL-2026-501';
UPDATE aseguradora_provincia.poliza SET imei = '358000000000056' WHERE numero = 'POL-CEL-2026-777';

COMMIT;

-- Check: the 7 Celulares policies with an IMEI, the laptop one NULL.
SELECT 'bbva' AS schema, numero, rama, imei FROM aseguradora_bbva.poliza
UNION ALL
SELECT 'provincia', numero, rama, imei FROM aseguradora_provincia.poliza
ORDER BY schema, numero;
