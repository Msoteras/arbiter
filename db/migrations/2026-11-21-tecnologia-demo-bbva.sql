-- 2026-11-21 · The final presentation runs entirely on BBVA, Tecnología Portátil cases included.
-- 2026-11-18-asegurados-demo-fast-track.sql loaded those cases in Provincia. This moves them:
--   · Tomás Ibarra      DNI 37.905.162  POL-TEC-2026-640  case 1 fallback (Robo, 1.299.999 / 3.000.000)
--   · Valentín Aguirre  DNI 38.614.270  POL-TEC-2026-782  case 2 (Rotura accidental, 445.000 / 600.000)
--   · Roman Castillo    DNI 33.845.219  POL-TEC-2026-352  case 2 seed, the earlier claim with the same photo
-- Valentín and Roman were already BBVA insureds. Valentín keeps his Provincia policy (one login,
-- two insurers), so his BBVA number differs from POL-TEC-2026-781. Tomás gets his own mailbox here and
-- Provincia's row goes back to the team alias, so his mailbox only receives the BBVA invitation.
-- Insurer databases only. Run it before BBVA's "Dar de alta asegurados". Keep in sync with perfiles.js.
-- Idempotent: looked up by DNI and policy number.

BEGIN;

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.asegurado', 'id'),
              (SELECT max(id) FROM aseguradora_bbva.asegurado));
SELECT setval(pg_get_serial_sequence('aseguradora_bbva.poliza', 'id'),
              (SELECT max(id) FROM aseguradora_bbva.poliza));
SELECT setval(pg_get_serial_sequence('aseguradora_bbva.cobertura', 'id'),
              (SELECT max(id) FROM aseguradora_bbva.cobertura));

INSERT INTO aseguradora_bbva.asegurado (documento, cuil, nombre, apellido, email, telefono)
SELECT '37.905.162', '20-37905162-7', U&'Tom\00E1s', 'Ibarra', 'ibarratomas087@gmail.com', '221-555-0640'
 WHERE NOT EXISTS (SELECT 1 FROM aseguradora_bbva.asegurado WHERE documento = '37.905.162');

UPDATE aseguradora_provincia.asegurado
   SET email = 'asandoval01228+tomas@gmail.com'
 WHERE documento = '37.905.162'
   AND email = 'ibarratomas087@gmail.com';

-- Like BBVA's other Tecnología Portátil policies: no insured item, the insured names the device
-- when filing, and the sum covers all of the insured's portable devices.
INSERT INTO aseguradora_bbva.poliza (numero, nro_certificado, titular_id, rama, producto, vigencia_desde,
                                     vigencia_hasta, estado_contrato, estado_pago, cuotas_pagas,
                                     cuotas_impagas, saldo_deuda, forma_pago, max_eventos_anuales,
                                     segundo_evento_pct, cubre_grupo_familiar, importe_cuota)
SELECT p.numero, p.certificado, a.id, U&'Tecnolog\00EDa Port\00E1til', U&'Tecnolog\00EDa Protegida',
       p.desde, p.hasta, 'ACTIVA', 'AL_DIA', p.pagas, 0, 0.00, p.forma, 2, 50.00, FALSE, p.cuota
  FROM (VALUES ('POL-TEC-2026-640', '800640', '37.905.162', TIMESTAMP '2026-07-01 00:00:00',
                TIMESTAMP '2027-07-01 23:59:59', 5, 'TARJETA DE CREDITO', 14900.00),
               ('POL-TEC-2026-782', '800782', '38.614.270', TIMESTAMP '2026-07-01 00:00:00',
                TIMESTAMP '2027-07-01 23:59:59', 5, 'DEBITO', 11900.00),
               ('POL-TEC-2026-352', '800352', '33.845.219', TIMESTAMP '2026-03-01 00:00:00',
                TIMESTAMP '2027-03-01 23:59:59', 9, 'TARJETA DE CREDITO', 11900.00))
       AS p (numero, certificado, documento, desde, hasta, pagas, forma, cuota)
  JOIN aseguradora_bbva.asegurado a ON a.documento = p.documento
 WHERE NOT EXISTS (SELECT 1 FROM aseguradora_bbva.poliza x WHERE x.numero = p.numero);

INSERT INTO aseguradora_bbva.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct)
SELECT p.id, c.orden, c.nombre, c.suma, 10.00
  FROM aseguradora_bbva.poliza p
  JOIN (VALUES ('POL-TEC-2026-640', 1, 'Robo de celular',          3000000.00),
               ('POL-TEC-2026-640', 2, 'Hurto',                    1500000.00),
               ('POL-TEC-2026-640', 3, U&'Da\00F1o accidental',    1500000.00),
               ('POL-TEC-2026-782', 1, 'Robo de celular',          2400000.00),
               ('POL-TEC-2026-782', 2, 'Hurto',                    1200000.00),
               ('POL-TEC-2026-782', 3, U&'Da\00F1o accidental',     600000.00),
               ('POL-TEC-2026-352', 1, 'Robo de celular',          2400000.00),
               ('POL-TEC-2026-352', 2, 'Hurto',                    1200000.00),
               ('POL-TEC-2026-352', 3, U&'Da\00F1o accidental',     600000.00))
       AS c (numero, orden, nombre, suma) ON c.numero = p.numero
 WHERE NOT EXISTS (SELECT 1 FROM aseguradora_bbva.cobertura x WHERE x.poliza_id = p.id);

COMMIT;
