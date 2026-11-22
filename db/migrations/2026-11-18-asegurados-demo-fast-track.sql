-- 2026-11-18 · Two clean insureds for the final presentation's Fast Track case, one per insurer.
-- No claims, no history, no account yet: every filed case counts as a prior claim and Fast Track
-- admits none, so the demo needs people nobody has used.
--   · BBVA       Sofía Montenegro  DNI 40.218.736  POL-CEL-2026-612  Celulares, Samsung Galaxy A16
--   · Provincia  Tomás Ibarra      DNI 37.905.162  POL-TEC-2026-640  Tecnología Portátil, global sum
-- Insurer databases only: the account, the Arbiter insured and the local policy copy come from the
-- referent's bulk provisioning and PolicySynchronizer. Keep in sync with perfiles.js.
--
-- Market-like values. The robbery in docs/postman/test-docs/demo/ claims under 50% of the Robo sum,
-- so it fast-tracks: a 429.999 phone on a basic plan of 900.000, and a 1.299.999 laptop on a
-- 3.000.000 sum that covers all the insured's portable devices. In force since 01/07/2026, past the
-- 30-day waiting period. Paid up, no arrears.
--
-- Also the presentation's second case: Valentín Aguirre (already a BBVA insured, with an account)
-- gets a Tecnología Portátil policy in Provincia, POL-TEC-2026-781. His accidental damage claim asks
-- 445.000 of a 600.000 sum (0.74), over Fast Track's 0.6, so it reaches the analyst. Provincia's bulk
-- provisioning adds the insurer to his existing user: same email, one login.
-- The emails are real mailboxes on purpose: the invitation goes there.
-- Text literals use U&'...' escapes because a PowerShell pipe once turned an accent into '?'.
-- Idempotent: looked up by DNI and policy number, nothing is inserted twice.

BEGIN;

-- The serial ids are left to the sequences, but BBVA's policies 15-16 and their coverages were loaded
-- with fixed ids and left poliza_id_seq and cobertura_id_seq behind. Catch every sequence up first.
SELECT setval(pg_get_serial_sequence('aseguradora_bbva.asegurado', 'id'),
              (SELECT max(id) FROM aseguradora_bbva.asegurado));
SELECT setval(pg_get_serial_sequence('aseguradora_bbva.poliza', 'id'),
              (SELECT max(id) FROM aseguradora_bbva.poliza));
SELECT setval(pg_get_serial_sequence('aseguradora_bbva.cobertura', 'id'),
              (SELECT max(id) FROM aseguradora_bbva.cobertura));
SELECT setval(pg_get_serial_sequence('aseguradora_provincia.asegurado', 'id'),
              (SELECT max(id) FROM aseguradora_provincia.asegurado));
SELECT setval(pg_get_serial_sequence('aseguradora_provincia.poliza', 'id'),
              (SELECT max(id) FROM aseguradora_provincia.poliza));
SELECT setval(pg_get_serial_sequence('aseguradora_provincia.cobertura', 'id'),
              (SELECT max(id) FROM aseguradora_provincia.cobertura));

-- ─── BBVA ─────────────────────────────────────────────────────────────────────
INSERT INTO aseguradora_bbva.asegurado (documento, cuil, nombre, apellido, email, telefono)
SELECT '40.218.736', '27-40218736-6', U&'Sof\00EDa', 'Montenegro',
       'asandoval01228+sofia@gmail.com', '11-5555-0512'
 WHERE NOT EXISTS (SELECT 1 FROM aseguradora_bbva.asegurado WHERE documento = '40.218.736');

-- Basic plan, like POL-CEL-2026-118: Robo and Hurto, no accidental damage.
INSERT INTO aseguradora_bbva.poliza (numero, nro_certificado, titular_id, rama, producto, bien_asegurado,
                                     imei, vigencia_desde, vigencia_hasta, estado_contrato, estado_pago,
                                     cuotas_pagas, cuotas_impagas, saldo_deuda, forma_pago,
                                     cubre_grupo_familiar, importe_cuota)
SELECT 'POL-CEL-2026-612', '621612', a.id, 'Celulares', U&'Celular Protegido B\00E1sico',
       'Samsung Galaxy A16', '354816270935167', '2026-07-01', '2027-07-01 23:59:59', 'ACTIVA', 'AL_DIA',
       5, 0, 0.00, 'TARJETA DE CREDITO', FALSE, 6890.00
  FROM aseguradora_bbva.asegurado a
 WHERE a.documento = '40.218.736'
   AND NOT EXISTS (SELECT 1 FROM aseguradora_bbva.poliza WHERE numero = 'POL-CEL-2026-612');

INSERT INTO aseguradora_bbva.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct)
SELECT p.id, c.orden, c.nombre, c.suma, c.franquicia
  FROM aseguradora_bbva.poliza p
 CROSS JOIN (VALUES (1, 'Robo de celular', 900000.00, 10.00),
                    (2, 'Hurto',           450000.00, 15.00)) AS c (orden, nombre, suma, franquicia)
 WHERE p.numero = 'POL-CEL-2026-612'
   AND NOT EXISTS (SELECT 1 FROM aseguradora_bbva.cobertura x WHERE x.poliza_id = p.id);

-- ─── Provincia ────────────────────────────────────────────────────────────────
INSERT INTO aseguradora_provincia.asegurado (documento, cuil, nombre, apellido, email, telefono)
SELECT '37.905.162', '20-37905162-7', U&'Tom\00E1s', 'Ibarra',
       'asandoval01228+tomas@gmail.com', '221-555-0640'
 WHERE NOT EXISTS (SELECT 1 FROM aseguradora_provincia.asegurado WHERE documento = '37.905.162');

-- Like Provincia's other Tecnología Portátil policies: no insured item, the insured names the device
-- when filing.
INSERT INTO aseguradora_provincia.poliza (numero, nro_certificado, titular_id, rama, producto, bien_asegurado,
                                          vigencia_desde, vigencia_hasta, estado_contrato, estado_pago,
                                          cuotas_pagas, cuotas_impagas, saldo_deuda, forma_pago,
                                          max_eventos_anuales, segundo_evento_pct, cubre_grupo_familiar,
                                          importe_cuota, datos_proveedor)
SELECT 'POL-TEC-2026-640', '700964', a.id, U&'Tecnolog\00EDa Port\00E1til',
       U&'Seguro de Tecnolog\00EDa Port\00E1til', NULL, '2026-07-01', '2027-07-01 23:59:59',
       'ACTIVA', 'AL_DIA', 5, 0, 0.00, 'TARJETA DE CREDITO', 2, 50.00, FALSE, 14900.00,
       '{"codRamaSegR":7,"nroPolizaR":2365640,"nroCertificadoR":700964,"descProductoR":"07 150 TEC PORT","importePrimaTarifa":12113.82,"importePremio":14900.00,"clausulaAjuste":"AJUSTE TASA FIJA","codClausulaAjuste":105}'::jsonb
  FROM aseguradora_provincia.asegurado a
 WHERE a.documento = '37.905.162'
   AND NOT EXISTS (SELECT 1 FROM aseguradora_provincia.poliza WHERE numero = 'POL-TEC-2026-640');

INSERT INTO aseguradora_provincia.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct)
SELECT p.id, c.orden, c.nombre, c.suma, 10.00
  FROM aseguradora_provincia.poliza p
 CROSS JOIN (VALUES (1, 'Robo de celular',          3000000.00),
                    (2, 'Hurto',                    1500000.00),
                    (3, U&'Da\00F1o accidental',    1500000.00)) AS c (orden, nombre, suma)
 WHERE p.numero = 'POL-TEC-2026-640'
   AND NOT EXISTS (SELECT 1 FROM aseguradora_provincia.cobertura x WHERE x.poliza_id = p.id);

-- ─── Provincia: Valentín's laptop policy (second case) ────────────────────────
-- Same person and email as his BBVA row, so bulk provisioning links his existing account.
INSERT INTO aseguradora_provincia.asegurado (documento, cuil, nombre, apellido, email, telefono)
SELECT '38.614.270', '20-38614270-0', U&'Valent\00EDn', 'Aguirre', 'aylusandu@gmail.com', '11-5555-0077'
 WHERE NOT EXISTS (SELECT 1 FROM aseguradora_provincia.asegurado WHERE documento = '38.614.270');

INSERT INTO aseguradora_provincia.poliza (numero, nro_certificado, titular_id, rama, producto, bien_asegurado,
                                          vigencia_desde, vigencia_hasta, estado_contrato, estado_pago,
                                          cuotas_pagas, cuotas_impagas, saldo_deuda, forma_pago,
                                          max_eventos_anuales, segundo_evento_pct, cubre_grupo_familiar,
                                          importe_cuota, datos_proveedor)
SELECT 'POL-TEC-2026-781', '700981', a.id, U&'Tecnolog\00EDa Port\00E1til',
       U&'Seguro de Tecnolog\00EDa Port\00E1til', NULL, '2026-07-01', '2027-07-01 23:59:59',
       'ACTIVA', 'AL_DIA', 5, 0, 0.00, 'DEBITO', 2, 50.00, FALSE, 11900.00,
       '{"codRamaSegR":7,"nroPolizaR":2365781,"nroCertificadoR":700981,"descProductoR":"07 150 TEC PORT","importePrimaTarifa":9674.80,"importePremio":11900.00,"clausulaAjuste":"AJUSTE TASA FIJA","codClausulaAjuste":105}'::jsonb
  FROM aseguradora_provincia.asegurado a
 WHERE a.documento = '38.614.270'
   AND NOT EXISTS (SELECT 1 FROM aseguradora_provincia.poliza WHERE numero = 'POL-TEC-2026-781');

INSERT INTO aseguradora_provincia.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct)
SELECT p.id, c.orden, c.nombre, c.suma, 10.00
  FROM aseguradora_provincia.poliza p
 CROSS JOIN (VALUES (1, 'Robo de celular',          2400000.00),
                    (2, 'Hurto',                    1200000.00),
                    (3, U&'Da\00F1o accidental',     600000.00)) AS c (orden, nombre, suma)
 WHERE p.numero = 'POL-TEC-2026-781'
   AND NOT EXISTS (SELECT 1 FROM aseguradora_provincia.cobertura x WHERE x.poliza_id = p.id);

COMMIT;

-- Check: ya_tiene_cuenta must be f for Sofía and Tomás until bulk provisioning runs, and t for Valentín
-- (his BBVA account); coberturas, 2 in BBVA and 3 in Provincia.
SELECT 'bbva' AS aseguradora, a.documento, a.nombre, a.apellido, a.email, p.numero,
       (SELECT count(*) FROM aseguradora_bbva.cobertura c WHERE c.poliza_id = p.id) AS coberturas,
       (u.id IS NOT NULL) AS ya_tiene_cuenta
  FROM aseguradora_bbva.asegurado a
  JOIN aseguradora_bbva.poliza p ON p.titular_id = a.id
  LEFT JOIN arbiter_common.users u ON lower(u.email) = lower(a.email)
 WHERE a.documento = '40.218.736'
UNION ALL
SELECT 'provincia', a.documento, a.nombre, a.apellido, a.email, p.numero,
       (SELECT count(*) FROM aseguradora_provincia.cobertura c WHERE c.poliza_id = p.id),
       (u.id IS NOT NULL)
  FROM aseguradora_provincia.asegurado a
  JOIN aseguradora_provincia.poliza p ON p.titular_id = a.id
  LEFT JOIN arbiter_common.users u ON lower(u.email) = lower(a.email)
 WHERE a.documento IN ('37.905.162', '38.614.270');
