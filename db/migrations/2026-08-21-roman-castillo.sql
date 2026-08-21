-- =============================================================================
-- 2026-08-21 · Roman Castillo — identidad y póliza para el set de fixtures sinMarca
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene datos
-- (Railway) sin pasar por el trío reset → init → seed.
--
-- Qué agrega:
--   · arbiter_common.users(9) + user_role + user_insurer: login de Roman (BBVA + Provincia).
--   · aseguradora_bbva/provincia.asegurado + poliza + cobertura: su propia póliza en
--     cada tenant, mismos números que Martina (POL-CEL-2026-042 / POL-TEC-2026-311) para
--     que el caso se comporte igual, sin pisar los de ella.
--   · arbiter_bbva/provincia.insured + policy: las copias locales correspondientes.
--
-- Por qué: docs/postman/test-docs/perfiles.js define dos firmantes para los mismos
-- escenarios de prueba — Martina (conMarcaDePrueba, con la leyenda "documento simulado")
-- y Roman (sinMarca, sin leyenda, para que el modelo de visión no lea un cartel que le
-- anticipa que el documento es de prueba). El login de Roman ya estaba en
-- init-multitenant.sql, pero sin póliza propia sus fixtures apuntaban al DNI de él contra
-- la póliza de Martina — PolicyEligibilityValidator (D2) lo rechaza: el DNI del payload
-- tiene que ser el titular de la póliza, no cualquier DNI logueado.
--
-- IMPORTANTE: los ids son fijos, verificados contra el max(id) real de Railway antes de
-- aplicar (no solo contra lo que carga seed-demo.sql en una base recién inicializada).
-- arbiter_bbva.policy en particular difiere de una base limpia: tiene filas 13-16 que son
-- basura sobrante del bug ya documentado en PolicyTenantLocator (16/8) — pólizas de
-- Provincia que un snapshot viejo dejó mal escritas en el esquema de BBVA. No se tocan acá
-- (no es parte de esta migración); el id de Roman en esa tabla arranca en 17, no en 11
-- como en una base limpia (ver PART 7 de seed-demo.sql, que sí usa 11 porque ahí no hay
-- basura que esquivar).
--
-- Idempotente: ON CONFLICT (id) DO NOTHING en cada INSERT, se puede correr más de una vez.
-- =============================================================================

BEGIN;

-- ─── Identidad (arbiter_common) ───────────────────────────────────────────────
-- TODO: 'auth0|seed-asegurado-roman' es un placeholder, no el sub real de Auth0 — hay
-- que reemplazarlo cuando Fede dé de alta asandoval01228@gmail.com (mismo tratamiento
-- que tuvo user(5)/Julián, que pasó de placeholder a 6a71248c6b9165b91b479173).
INSERT INTO arbiter_common.users (id, auth0_sub, email, active, activated) VALUES
    (9, 'auth0|seed-asegurado-roman', 'asandoval01228@gmail.com', TRUE, TRUE)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('arbiter_common.users', 'id'),
              (SELECT MAX(id) FROM arbiter_common.users));

INSERT INTO arbiter_common.user_role (user_id, role_id) VALUES (9, 1)
ON CONFLICT DO NOTHING;

INSERT INTO arbiter_common.user_insurer (user_id, insurer_id) VALUES (9, 1), (9, 2)
ON CONFLICT DO NOTHING;

-- ─── BD Aseguradora: BBVA ──────────────────────────────────────────────────────
INSERT INTO aseguradora_bbva.asegurado (id, documento, cuil, nombre, apellido, email, telefono) VALUES
    (3, '33.845.219', '20-33845219-6', 'Roman', 'Castillo', 'asandoval01228@gmail.com', '11-5555-0007')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.asegurado','id'),
              (SELECT MAX(id) FROM aseguradora_bbva.asegurado));

-- Mismo Samsung A56, mismos números que la póliza 1 de Martina (POL-CEL-2026-042): los
-- fixtures de sinMarca describen el mismo escenario, narrado por Roman en vez de ella.
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

-- ─── BD Aseguradora: Provincia ─────────────────────────────────────────────────
INSERT INTO aseguradora_provincia.asegurado (id, documento, cuil, nombre, apellido, email, telefono) VALUES
    (3, '33.845.219', '20-33845219-6', 'Roman', 'Castillo', 'asandoval01228@gmail.com', '11-5555-0007')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_provincia.asegurado','id'),
              (SELECT MAX(id) FROM aseguradora_provincia.asegurado));

-- Mismo MacBook Air M3 15", mismos números que la póliza 1 de Martina (POL-TEC-2026-311).
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

-- ─── Copias locales (tenant Arbiter) ────────────────────────────────────────────
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

-- coverage_id 3 = 'Daño accidental' (arbiter_provincia.coverage) — la misma que apunta la
-- póliza de Tecnología de Martina.
INSERT INTO arbiter_provincia.policy (id, external_policy_number, product, sum_insured, in_force, insured_id, coverage_id) VALUES
    (8, 'POL-TEC-2026-350', 'Seguro de Tecnología Portátil', 90000.00, TRUE, 3, 3)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('arbiter_provincia.policy','id'),
              (SELECT MAX(id) FROM arbiter_provincia.policy));

COMMIT;
