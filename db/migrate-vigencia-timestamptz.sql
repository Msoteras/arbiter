-- Migración manual, una vez, contra la BD viva (Railway). No hay Flyway: db/init-multitenant.sql
-- solo corre al crear el volumen desde cero, así que los cambios de esquema no llegan solos a una
-- base que ya tiene datos.
--
-- Historia de este archivo: primero se corrió con TIMESTAMPTZ (commit a28da47). Eso rompió
-- distinto: rs.getObject(col, LocalDateTime.class) en InsurerDatabaseAdapter (JDBC crudo, no JPA)
-- no puede leer una columna con timezone — el driver de Postgres exige OffsetDateTime/Instant para
-- TIMESTAMPTZ. Este script corrige a TIMESTAMP (sin timezone), que es lo que quedó en
-- db/init-multitenant.sql. AT TIME ZONE 'UTC' en el USING para que la conversión sea determinista
-- sin depender del timezone de la sesión que corra esto (los valores se cargaron en UTC).

BEGIN;

ALTER TABLE aseguradora_bbva.poliza
    ALTER COLUMN vigencia_desde TYPE TIMESTAMP USING (vigencia_desde AT TIME ZONE 'UTC'),
    ALTER COLUMN vigencia_hasta TYPE TIMESTAMP USING (vigencia_hasta AT TIME ZONE 'UTC');

ALTER TABLE aseguradora_provincia.poliza
    ALTER COLUMN vigencia_desde TYPE TIMESTAMP USING (vigencia_desde AT TIME ZONE 'UTC'),
    ALTER COLUMN vigencia_hasta TYPE TIMESTAMP USING (vigencia_hasta AT TIME ZONE 'UTC');

COMMIT;

-- Verificación:
-- SELECT id, numero, vigencia_desde, vigencia_hasta FROM aseguradora_bbva.poliza ORDER BY id;
-- SELECT id, numero, vigencia_desde, vigencia_hasta FROM aseguradora_provincia.poliza ORDER BY id;
