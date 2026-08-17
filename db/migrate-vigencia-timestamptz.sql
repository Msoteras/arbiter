-- Migración manual, una vez, contra la BD viva (Railway). No hay Flyway: db/init-multitenant.sql
-- solo corre al crear el volumen desde cero, así que este cambio (D13, vigencia con hora) nunca
-- se aplicó a la base que ya tenía datos. Este script lo hace a mano, preservando lo existente.

BEGIN;

ALTER TABLE aseguradora_bbva.poliza
    ALTER COLUMN vigencia_desde TYPE TIMESTAMPTZ USING vigencia_desde::timestamptz,
    ALTER COLUMN vigencia_hasta TYPE TIMESTAMPTZ USING (vigencia_hasta::date + time '23:59:59')::timestamptz;

ALTER TABLE aseguradora_provincia.poliza
    ALTER COLUMN vigencia_desde TYPE TIMESTAMPTZ USING vigencia_desde::timestamptz,
    ALTER COLUMN vigencia_hasta TYPE TIMESTAMPTZ USING (vigencia_hasta::date + time '23:59:59')::timestamptz;

-- Póliza modelo del proyecto (Proyecto Final/poliza.pdf), agregada al seed esta sesión pero nunca
-- insertada en la BD viva: vigencia con hora exacta, 12:00 a 12:00.
INSERT INTO aseguradora_bbva.poliza (id, numero, nro_certificado, titular_id, rama, producto, bien_asegurado,
                                     imei, vigencia_desde, vigencia_hasta, estado_contrato, estado_pago,
                                     cuotas_pagas, cuotas_impagas, saldo_deuda, forma_pago, cubre_grupo_familiar)
VALUES (11, '2364698', '621399', 1, 'Celulares', 'Celular Protegido Premium', 'Samsung Galaxy A55',
        '360000000002364', '2026-06-14 12:00:00', '2026-09-14 12:00:00', 'ACTIVA', 'AL_DIA',
        3, 0, 0.00, 'TARJETA DE CREDITO', FALSE)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.poliza', 'id'),
              (SELECT MAX(id) FROM aseguradora_bbva.poliza));

INSERT INTO aseguradora_bbva.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct)
SELECT 11, 1, 'Robo de celular', 1300000.00, 10.00
WHERE NOT EXISTS (SELECT 1 FROM aseguradora_bbva.cobertura WHERE poliza_id = 11 AND orden = 1);

COMMIT;

-- Verificación:
-- SELECT id, numero, vigencia_desde, vigencia_hasta FROM aseguradora_bbva.poliza ORDER BY id;
-- SELECT id, numero, vigencia_desde, vigencia_hasta FROM aseguradora_provincia.poliza ORDER BY id;
