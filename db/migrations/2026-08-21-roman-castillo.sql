-- 2026-08-21 · Roman Castillo: login, insurer records and local copies for the sinMarca fixtures,
-- with Martina's policy numbers so the scenarios behave the same. Ids checked against Railway's
-- max(id): arbiter_bbva.policy starts at 17 because of the leaked rows 13-16. Idempotent.

BEGIN;

-- TODO: 'auth0|seed-asegurado-roman' is a placeholder until his Auth0 account exists.
INSERT INTO arbiter_common.users (id, auth0_sub, email, active, activated) VALUES
    (9, 'auth0|seed-asegurado-roman', 'asandoval01228@gmail.com', TRUE, TRUE)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('arbiter_common.users', 'id'),
              (SELECT MAX(id) FROM arbiter_common.users));

INSERT INTO arbiter_common.user_role (user_id, role_id) VALUES (9, 1)
ON CONFLICT DO NOTHING;

INSERT INTO arbiter_common.user_insurer (user_id, insurer_id) VALUES (9, 1), (9, 2)
ON CONFLICT DO NOTHING;

-- Insurer database: BBVA
INSERT INTO aseguradora_bbva.asegurado (id, documento, cuil, nombre, apellido, email, telefono) VALUES
    (3, '33.845.219', '20-33845219-6', 'Roman', 'Castillo', 'asandoval01228@gmail.com', '11-5555-0007')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.asegurado','id'),
              (SELECT MAX(id) FROM aseguradora_bbva.asegurado));

-- Same Samsung A56 and numbers as Martina's policy 1 (POL-CEL-2026-042).
INSERT INTO aseguradora_bbva.poliza (id, numero, nro_certificado, titular_id, rama, producto, bien_asegurado,
                                     imei, vigencia_desde, vigencia_hasta, estado_contrato, estado_pago,
                                     cuotas_pagas, cuotas_impagas, saldo_deuda, forma_pago, cubre_grupo_familiar) VALUES
    (12, 'POL-CEL-2026-350', '621350', 3, 'Celulares', 'Celular Protegido Premium', 'Samsung Galaxy A56',
     '359000000000350', '2026-01-01','2027-01-01 23:59:59','ACTIVA','AL_DIA', 6, 0, 0.00, 'TARJETA DE CREDITO', TRUE)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.poliza','id'),
              (SELECT MAX(id) FROM aseguradora_bbva.poliza));

INSERT INTO aseguradora_bbva.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct) VALUES
    (12, 1, 'Robo de celular', 1300000.00, 10.00),
    (12, 2, 'Hurto',            650000.00, 10.00)
ON CONFLICT DO NOTHING;

-- Insurer database: Provincia
INSERT INTO aseguradora_provincia.asegurado (id, documento, cuil, nombre, apellido, email, telefono) VALUES
    (3, '33.845.219', '20-33845219-6', 'Roman', 'Castillo', 'asandoval01228@gmail.com', '11-5555-0007')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_provincia.asegurado','id'),
              (SELECT MAX(id) FROM aseguradora_provincia.asegurado));

-- Same MacBook Air M3 15" and numbers as Martina's policy 1 (POL-TEC-2026-311).
INSERT INTO aseguradora_provincia.poliza (id, numero, nro_certificado, titular_id, rama, producto, bien_asegurado,
                                          imei, vigencia_desde, vigencia_hasta, estado_contrato, estado_pago,
                                          cuotas_pagas, cuotas_impagas, saldo_deuda, forma_pago,
                                          max_eventos_anuales, segundo_evento_pct, cubre_grupo_familiar,
                                          datos_proveedor) VALUES
    (8, 'POL-TEC-2026-350', '700910', 3, 'Tecnología Portátil', 'Seguro de Tecnología Portátil', 'MacBook Air M3 15"',
     NULL, '2026-03-01','2027-03-01 23:59:59','ACTIVA','AL_DIA', 4, 0, 0.00, 'TARJETA DE CREDITO', 2, 50.00, FALSE,
     '{"codRamaSegR":7,"nroPolizaR":2365304,"nroCertificadoR":700910,"descProductoR":"07 150 TEC PORT","importePrimaTarifa":2762.5,"importePremio":3406.17,"clausulaAjuste":"AJUSTE TASA FIJA","codClausulaAjuste":105}'::jsonb)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_provincia.poliza','id'),
              (SELECT MAX(id) FROM aseguradora_provincia.poliza));

INSERT INTO aseguradora_provincia.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct) VALUES
    (8, 1, 'Robo de celular', 170000.00, 10.00),
    (8, 2, 'Daño accidental',  90000.00, 10.00)
ON CONFLICT DO NOTHING;

-- Local copies (Arbiter tenants)
INSERT INTO arbiter_bbva.insured (id, name, surname, dni, email, phone, case_count, pep, user_id) VALUES
    (3, 'Roman', 'Castillo', '33.845.219', 'asandoval01228@gmail.com', '11-5555-0007', 0, FALSE, 9)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('arbiter_bbva.insured','id'),
              (SELECT MAX(id) FROM arbiter_bbva.insured));

INSERT INTO arbiter_bbva.policy (id, external_policy_number, product, sum_insured, in_force, insured_id, coverage_id) VALUES
    (17, 'POL-CEL-2026-350', 'Celular Protegido Premium', 1300000.00, TRUE, 3, 1)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('arbiter_bbva.policy','id'),
              (SELECT MAX(id) FROM arbiter_bbva.policy));

INSERT INTO arbiter_provincia.insured (id, name, surname, dni, email, phone, case_count, pep, user_id) VALUES
    (3, 'Roman', 'Castillo', '33.845.219', 'asandoval01228@gmail.com', '11-5555-0007', 0, FALSE, 9)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('arbiter_provincia.insured','id'),
              (SELECT MAX(id) FROM arbiter_provincia.insured));

-- coverage_id 3 = 'Daño accidental', the one Martina's laptop policy points at.
INSERT INTO arbiter_provincia.policy (id, external_policy_number, product, sum_insured, in_force, insured_id, coverage_id) VALUES
    (8, 'POL-TEC-2026-350', 'Seguro de Tecnología Portátil', 90000.00, TRUE, 3, 3)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('arbiter_provincia.policy','id'),
              (SELECT MAX(id) FROM arbiter_provincia.policy));

COMMIT;
