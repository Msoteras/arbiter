-- 2026-09-22 · Valentín Aguirre: a BBVA insured with no history, only for the mutations (every filed case
-- counts as a previous claim and trips the Fast Track cap). Insurer database only: the account and the
-- policy copy come from bulk provisioning and PolicySynchronizer. Keep in sync with perfiles.js. Idempotent.

BEGIN;

-- The email is a real mailbox on purpose: the bulk provisioning sends the invitation there, and
-- an account nobody can log into can't file a case.
INSERT INTO aseguradora_bbva.asegurado (id, documento, cuil, nombre, apellido, email, telefono) VALUES
    (5, '38.614.270', '20-38614270-0', 'Valentín', 'Aguirre', 'aylusandu@gmail.com', '11-5555-0077')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.asegurado', 'id'),
              (SELECT MAX(id) FROM aseguradora_bbva.asegurado));

-- In force today: bulk provisioning only looks at vigencia_hasta >= NOW().
INSERT INTO aseguradora_bbva.poliza (id, numero, nro_certificado, titular_id, rama, producto,
                                     bien_asegurado, imei, vigencia_desde, vigencia_hasta,
                                     estado_contrato, estado_pago, cuotas_pagas, cuotas_impagas,
                                     saldo_deuda, forma_pago, cubre_grupo_familiar) VALUES
    (14, 'POL-CEL-2026-777', '700977', 5, 'Celulares', 'Celular Protegido Premium',
     'Samsung Galaxy A56', '357000000000777', '2026-01-01', '2027-01-01 23:59:59',
     'ACTIVA', 'AL_DIA', 8, 0, 0.00, 'TARJETA DE CREDITO', FALSE)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.poliza', 'id'),
              (SELECT MAX(id) FROM aseguradora_bbva.poliza));

INSERT INTO aseguradora_bbva.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct) VALUES
    (14, 1, 'Robo de celular', 1300000.00, 10.00),
    (14, 2, 'Hurto',            650000.00, 10.00)
ON CONFLICT DO NOTHING;

COMMIT;

-- Check: ya_tiene_cuenta must be f until bulk provisioning runs.
SELECT DISTINCT a.documento, a.nombre, a.apellido, a.email,
       (u.id IS NOT NULL) AS ya_tiene_cuenta
  FROM aseguradora_bbva.asegurado a
  JOIN aseguradora_bbva.poliza p ON p.titular_id = a.id
  LEFT JOIN arbiter_common.users u ON lower(u.email) = lower(a.email)
 WHERE a.documento = '38.614.270';
