-- 2026-08-17 · Policy validity back to TIMESTAMP without time zone: InsurerDatabaseAdapter reads it
-- as LocalDateTime over raw JDBC, which fails on TIMESTAMPTZ. Values were loaded in UTC.

BEGIN;

ALTER TABLE aseguradora_bbva.poliza
    ALTER COLUMN vigencia_desde TYPE TIMESTAMP USING (vigencia_desde AT TIME ZONE 'UTC'),
    ALTER COLUMN vigencia_hasta TYPE TIMESTAMP USING (vigencia_hasta AT TIME ZONE 'UTC');

ALTER TABLE aseguradora_provincia.poliza
    ALTER COLUMN vigencia_desde TYPE TIMESTAMP USING (vigencia_desde AT TIME ZONE 'UTC'),
    ALTER COLUMN vigencia_hasta TYPE TIMESTAMP USING (vigencia_hasta AT TIME ZONE 'UTC');

COMMIT;

-- Check:
-- SELECT id, numero, vigencia_desde, vigencia_hasta FROM aseguradora_bbva.poliza ORDER BY id;
-- SELECT id, numero, vigencia_desde, vigencia_hasta FROM aseguradora_provincia.poliza ORDER BY id;
