-- 2026-08-25 · Camila Ferreyra: a BBVA insured with NO platform account, to test bulk provisioning
-- end to end. Only the insurer database is touched on purpose: the account is what the button has
-- to create. Coverage names match arbiter_bbva.coverage, which PolicySynchronizer matches by name.

BEGIN;

INSERT INTO aseguradora_bbva.asegurado (id, documento, cuil, nombre, apellido, email, telefono) VALUES
    (4, '38.412.905', '27-38412905-4', 'Camila', 'Ferreyra',
     'asandoval01228+alta@gmail.com', '11-5555-0042')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.asegurado', 'id'),
              (SELECT MAX(id) FROM aseguradora_bbva.asegurado));

-- In force today: bulk provisioning only looks at vigencia_hasta >= NOW().
INSERT INTO aseguradora_bbva.poliza (id, numero, nro_certificado, titular_id, rama, producto,
                                     bien_asegurado, vigencia_desde, vigencia_hasta,
                                     estado_contrato, estado_pago, cuotas_pagas, cuotas_impagas,
                                     saldo_deuda, forma_pago, cubre_grupo_familiar) VALUES
    (13, 'POL-CEL-2026-401', '700945', 4, 'Celulares', 'Celular Protegido Premium',
     'Google Pixel 8', '2026-01-01', '2027-01-01 23:59:59',
     'ACTIVA', 'AL_DIA', 8, 0, 0.00, 'TARJETA DE CREDITO', FALSE)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.poliza', 'id'),
              (SELECT MAX(id) FROM aseguradora_bbva.poliza));

INSERT INTO aseguradora_bbva.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct) VALUES
    (13, 1, 'Robo de celular', 1300000.00, 10.00),
    (13, 2, 'Hurto',            650000.00, 10.00)
ON CONFLICT DO NOTHING;

COMMIT;

-- Check: ya_tiene_cuenta must be f, or bulk provisioning skips her.
SELECT DISTINCT a.documento, a.nombre, a.apellido, a.email,
       (u.id IS NOT NULL) AS ya_tiene_cuenta
  FROM aseguradora_bbva.asegurado a
  JOIN aseguradora_bbva.poliza p ON p.titular_id = a.id
  LEFT JOIN arbiter_common.users u ON lower(u.email) = lower(a.email)
 WHERE p.vigencia_hasta >= NOW()
 ORDER BY a.apellido;
