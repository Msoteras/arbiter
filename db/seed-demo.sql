-- =============================================================================
-- Arbiter — demo data, segmented by insurer
--
-- Run AFTER db/init-multitenant.sql, which creates the schemas, the catalogs and
-- the four seed users.
--
-- Ports the fixtures that db/init.sql and db/datos-aseguradoras.sql held in a single
-- schema, splitting them across the two tenants. The risk profiles are preserved
-- deliberately — each insured exercises a different corner of the scoring engine:
--
--   claim_frequency = min(1, previous_claims / 3)
--   policy_standing = 1.0 when the policy is in arrears, 0.0 when up to date
--   amount_ratio    = claimed_amount / coverage sum insured
--
--   BBVA        Martina  0 previous · up to date   → LOW
--               Julián   3 previous · up to date   → HIGH (repeat claimant)
--               Lucas    0 previous · low sums     → unscored (missing docs)
--               Nicolás  1 previous · up to date   → MEDIUM
--               Valeria  2 previous · up to date   → MEDIUM/HIGH
--               Diego    0 previous · IN ARREARS   → isolates policy_standing
--   PROVINCIA   Carla    0 previous · up to date   → LOW (resolved case)
--               Camila   0 previous · up to date   → LOW
--               Federico 2 previous · IN ARREARS   → HIGH (two factors at once)
--
-- Martina is a customer of BOTH insurers: one identity in arbiter_common.users, two
-- `insured` rows in different schemas. That is the case worth demoing — it shows the
-- shared-identity / isolated-data split actually working.
--
-- Usage:  psql "$DATABASE_URL" -f db/seed-demo.sql
-- =============================================================================

BEGIN;

-- =============================================================================
-- PART 1 — Shared identity (arbiter_common)
-- One user per insured person. Roles and membership decide what they can see and
-- which schema serves them.
-- =============================================================================

INSERT INTO arbiter_common.users (id, auth0_sub, email, active, activated) VALUES
    -- BBVA policyholders
    ( 5, 'auth0|seed-julian',   'julian.perez@example.com',     TRUE, TRUE),
    ( 6, 'auth0|seed-lucasm',   'lucas.martinez@example.com',   TRUE, TRUE),
    ( 7, 'auth0|seed-nicolas',  'nicolas.ferreyra@example.com', TRUE, TRUE),
    ( 8, 'auth0|seed-valeria',  'valeria.rios@example.com',     TRUE, TRUE),
    ( 9, 'auth0|seed-diegos',   'diego.sosa@example.com',       TRUE, TRUE),
    -- Provincia policyholders
    (10, 'auth0|seed-carla',    'carla.gomez@example.com',      TRUE, TRUE),
    (11, 'auth0|seed-camila',   'camila.duarte@example.com',    TRUE, TRUE),
    (12, 'auth0|seed-federico', 'federico.aguirre@example.com', TRUE, TRUE);

SELECT setval(pg_get_serial_sequence('arbiter_common.users', 'id'),
              (SELECT MAX(id) FROM arbiter_common.users));

INSERT INTO arbiter_common.user_role (user_id, role_id) VALUES
    (5,1), (6,1), (7,1), (8,1), (9,1), (10,1), (11,1), (12,1);

INSERT INTO arbiter_common.user_insurer (user_id, insurer_id) VALUES
    (5,1), (6,1), (7,1), (8,1), (9,1),
    (10,2), (11,2), (12,2),
    -- Martina (user 1, already a BBVA member) is also a Provincia customer.
    (1,2);

-- =============================================================================
-- PART 2 — "BD Aseguradora": BBVA (external system, read by InsurerDatabaseAdapter)
-- =============================================================================

INSERT INTO aseguradora_bbva.asegurado (id, documento, cuil, nombre, apellido, email, telefono) VALUES
    (1, '42.987.654', '27-42987654-1', 'Martina',  'Soteras',  'martina.soteras@example.com',  '11-5555-0001'),
    (2, '30.555.777', '20-30555777-3', 'Julián',   'Pérez',    'julian.perez@example.com',     '11-5555-0002'),
    (3, '44655366',   '20-44655366-9', 'Lucas',    'Martínez', 'lucas.martinez@example.com',   '11-5555-0003'),
    (4, '38.222.111', '20-38222111-5', 'Nicolás',  'Ferreyra', 'nicolas.ferreyra@example.com', '11-5555-0005'),
    (5, '29.888.444', '27-29888444-2', 'Valeria',  'Ríos',     'valeria.rios@example.com',     '11-5555-0006'),
    (6, '41.333.999', '20-41333999-7', 'Diego',    'Sosa',     'diego.sosa@example.com',       '11-5555-0007');

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.asegurado','id'),
              (SELECT MAX(id) FROM aseguradora_bbva.asegurado));

INSERT INTO aseguradora_bbva.poliza (id, numero, nro_certificado, titular_id, rama, producto, bien_asegurado,
                                     vigencia_desde, vigencia_hasta, estado_contrato, estado_pago,
                                     cuotas_pagas, cuotas_impagas, saldo_deuda, forma_pago, cubre_grupo_familiar) VALUES
    (1, 'POL-CEL-2026-042', '621242', 1, 'Celulares', 'Celular Protegido Premium', 'Samsung Galaxy A56',
     '2026-01-01','2027-01-01','ACTIVA','AL_DIA',       6, 0,       0.00, 'TARJETA DE CREDITO', TRUE),
    (2, 'POL-CEL-2025-099', '621243', 2, 'Celulares', 'Celular Protegido Premium', 'iPhone 15 Pro',
     '2025-11-01','2026-11-01','ACTIVA','AL_DIA',       8, 0,       0.00, 'TARJETA DE CREDITO', TRUE),
    (3, '2030405',          '621244', 3, 'Celulares', 'Celular Protegido Básico',  'Motorola Moto G54',
     '2026-01-15','2027-01-15','ACTIVA','AL_DIA',       5, 0,       0.00, 'DEBITO', TRUE),
    (4, 'POL-CEL-2026-118', '621245', 4, 'Celulares', 'Celular Protegido Básico',  'Samsung Galaxy S24',
     '2026-02-01','2027-02-01','ACTIVA','AL_DIA',       5, 0,       0.00, 'DEBITO', FALSE),
    (5, 'POL-CEL-2026-205', '621246', 5, 'Celulares', 'Celular Protegido Premium', 'iPhone 15',
     '2026-01-10','2027-01-10','ACTIVA','AL_DIA',       6, 0,       0.00, 'TARJETA DE CREDITO', TRUE),
    -- Diego: the only BBVA policy in arrears — isolates policy_standing from every other factor.
    (6, 'POL-CEL-2026-330', '621247', 6, 'Celulares', 'Celular Protegido Básico',  'Xiaomi Redmi Note 13',
     '2026-03-01','2027-03-01','ACTIVA','SUSPENDIDA',   2, 3, 18450.00, 'DEBITO', FALSE);

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.poliza','id'),
              (SELECT MAX(id) FROM aseguradora_bbva.poliza));

INSERT INTO aseguradora_bbva.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct) VALUES
    (1, 1, 'Robo de celular', 1300000.00, 10.00),
    (1, 2, 'Hurto',            650000.00, 10.00),
    (2, 1, 'Robo de celular', 1200000.00, 10.00),
    (3, 1, 'Robo de celular',  200000.00, 10.00),
    (3, 2, 'Hurto',             50000.00,  0.00),
    (4, 1, 'Robo de celular',  800000.00, 10.00),
    (5, 1, 'Robo de celular', 1500000.00, 10.00),
    (5, 2, 'Hurto',            400000.00, 15.00),
    (6, 1, 'Robo de celular',  600000.00, 10.00);

INSERT INTO aseguradora_bbva.siniestro_historico (poliza_id, asegurado_id, fecha_ocurrencia, causa,
                                                  estado_resolucion, monto_indemnizado) VALUES
    -- Julián: 3 previous → claim_frequency saturates at 1.0
    (2, 2, '2025-11-18', 'Robo en vía pública', 'LIQUIDADO', 640000.00),
    (2, 2, '2026-02-05', 'Robo en vía pública', 'LIQUIDADO', 710000.00),
    (2, 2, '2026-04-22', 'Robo en vía pública', 'RECHAZADO', NULL),
    -- Nicolás: 1 previous
    (4, 4, '2026-03-20', 'Robo en vía pública', 'LIQUIDADO', 380000.00),
    -- Valeria: 2 previous
    (5, 5, '2025-10-12', 'Hurto',               'LIQUIDADO', 520000.00),
    (5, 5, '2026-01-30', 'Robo en vía pública', 'RECHAZADO', NULL);
-- Martina, Lucas and Diego have none → claim_frequency = 0.

-- =============================================================================
-- PART 3 — "BD Aseguradora": Provincia
-- =============================================================================

INSERT INTO aseguradora_provincia.asegurado (id, documento, cuil, nombre, apellido, email, telefono) VALUES
    (1, '35.111.222', '27-35111222-4', 'Carla',    'Gómez',   'carla.gomez@example.com',      '11-5555-0004'),
    (2, '33.777.222', '27-33777222-8', 'Camila',   'Duarte',  'camila.duarte@example.com',    '11-5555-0008'),
    (3, '28.444.666', '20-28444666-1', 'Federico', 'Aguirre', 'federico.aguirre@example.com', '11-5555-0009'),
    -- Same document as BBVA's insured 1: Martina holds policies with both companies.
    (4, '42.987.654', '27-42987654-1', 'Martina',  'Soteras', 'martina.soteras@example.com',  '11-5555-0001');

SELECT setval(pg_get_serial_sequence('aseguradora_provincia.asegurado','id'),
              (SELECT MAX(id) FROM aseguradora_provincia.asegurado));

INSERT INTO aseguradora_provincia.poliza (id, numero, nro_certificado, titular_id, rama, producto, bien_asegurado,
                                          vigencia_desde, vigencia_hasta, estado_contrato, estado_pago,
                                          cuotas_pagas, cuotas_impagas, saldo_deuda, forma_pago,
                                          max_eventos_anuales, segundo_evento_pct, cubre_grupo_familiar,
                                          datos_proveedor) VALUES
    (1, 'POL-TEC-2026-311', '700841', 1, 'Tecnología Portátil', 'Seguro de Tecnología Portátil', 'MacBook Air M3 15"',
     '2026-03-01','2027-03-01','ACTIVA','AL_DIA', 4, 1, 3406.17, 'TARJETA DE CREDITO', 2, 50.00, FALSE,
     '{"codRamaSegR":7,"nroPolizaR":2365301,"nroCertificadoR":700841,"descProductoR":"07 150 TEC PORT","importePrimaTarifa":2762.5,"importePremio":3406.17,"clausulaAjuste":"AJUSTE TASA FIJA","codClausulaAjuste":105}'::jsonb),
    (2, 'POL-TEC-2026-402', '700842', 2, 'Tecnología Portátil', 'Seguro de Tecnología Portátil', 'iPad Pro 11"',
     '2026-02-01','2027-02-01','ACTIVA','AL_DIA', 5, 0, 0.00, 'DEBITO', 2, 50.00, FALSE, NULL),
    -- Federico: 2 previous claims AND in arrears — two risk factors firing at once.
    (3, 'POL-CEL-2026-501', '700843', 3, 'Celulares', 'Celular Protegido', 'Samsung Galaxy S23',
     '2026-01-05','2027-01-05','ACTIVA','SUSPENDIDA', 3, 2, 22100.00, 'DEBITO', NULL, NULL, FALSE, NULL),
    (4, 'POL-CEL-2026-777', '700844', 4, 'Celulares', 'Celular Protegido', 'Samsung Galaxy A56',
     '2026-04-01','2027-04-01','ACTIVA','AL_DIA', 3, 0, 0.00, 'TARJETA DE CREDITO', NULL, NULL, FALSE, NULL);

SELECT setval(pg_get_serial_sequence('aseguradora_provincia.poliza','id'),
              (SELECT MAX(id) FROM aseguradora_provincia.poliza));

INSERT INTO aseguradora_provincia.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct) VALUES
    (1, 1, 'Robo de celular', 170000.00, 10.00),
    (1, 2, 'Daño accidental',  90000.00, 10.00),
    (2, 1, 'Daño accidental', 250000.00, 10.00),
    (3, 1, 'Robo de celular', 900000.00, 10.00),
    (4, 1, 'Robo de celular', 700000.00, 10.00);

INSERT INTO aseguradora_provincia.siniestro_historico (poliza_id, asegurado_id, fecha_ocurrencia, causa,
                                                       estado_resolucion, monto_indemnizado) VALUES
    (3, 3, '2025-09-15', 'Robo en vía pública', 'LIQUIDADO', 410000.00),
    (3, 3, '2026-02-20', 'Robo en vía pública', 'LIQUIDADO', 480000.00);

-- =============================================================================
-- PART 4 — Arbiter tenant: BBVA
-- =============================================================================

-- insured id=1 (Martina) is already seeded by init-multitenant.sql.
INSERT INTO arbiter_bbva.insured (id, name, surname, dni, email, phone, case_count, pep, user_id) VALUES
    (2, 'Julián',  'Pérez',    '30.555.777', 'julian.perez@example.com',     '11-5555-0002', 1, FALSE, 5),
    (3, 'Lucas',   'Martínez', '44655366',   'lucas.martinez@example.com',   '11-5555-0003', 1, FALSE, 6),
    (4, 'Nicolás', 'Ferreyra', '38.222.111', 'nicolas.ferreyra@example.com', '11-5555-0005', 0, FALSE, 7),
    (5, 'Valeria', 'Ríos',     '29.888.444', 'valeria.rios@example.com',     '11-5555-0006', 0, FALSE, 8),
    (6, 'Diego',   'Sosa',     '41.333.999', 'diego.sosa@example.com',       '11-5555-0007', 0, FALSE, 9);

UPDATE arbiter_bbva.insured SET case_count = 3 WHERE id = 1;   -- Martina: cases 1, 4 and 5

SELECT setval(pg_get_serial_sequence('arbiter_bbva.insured','id'),
              (SELECT MAX(id) FROM arbiter_bbva.insured));

-- Local snapshots of the policies above. coverage 1 = 'Robo de celular', 2 = 'Hurto'.
INSERT INTO arbiter_bbva.policy (id, external_policy_number, product, sum_insured, in_force, insured_id, coverage_id) VALUES
    (1, 'POL-CEL-2026-042', 'Celular Protegido Premium', 1300000.00, TRUE, 1, 1),
    (2, 'POL-CEL-2025-099', 'Celular Protegido Premium', 1200000.00, TRUE, 2, 1),
    (3, '2030405',          'Celular Protegido Básico',   200000.00, TRUE, 3, 1),
    (4, 'POL-CEL-2026-118', 'Celular Protegido Básico',   800000.00, TRUE, 4, 1),
    (5, 'POL-CEL-2026-205', 'Celular Protegido Premium', 1500000.00, TRUE, 5, 1),
    (6, 'POL-CEL-2026-330', 'Celular Protegido Básico',   600000.00, TRUE, 6, 1);

SELECT setval(pg_get_serial_sequence('arbiter_bbva.policy','id'),
              (SELECT MAX(id) FROM arbiter_bbva.policy));

-- What the insurer answered when each claim was filed. Frozen: the classification must
-- stay reproducible even if the insurer's data changes afterwards.
INSERT INTO arbiter_bbva.policy_snapshot (id, external_policy_number, sum_insured, in_force,
                                          payments_up_to_date, previous_claims, queried_at) VALUES
    (1, 'POL-CEL-2026-042', 1300000.00, TRUE, TRUE, 0, '2026-06-14 08:35:00+00'),
    (2, 'POL-CEL-2025-099', 1200000.00, TRUE, TRUE, 3, '2026-06-11 09:10:00+00'),
    (3, '2030405',           200000.00, TRUE, TRUE, 0, '2026-06-30 10:05:00+00'),
    (4, 'POL-CEL-2026-042', 1300000.00, TRUE, TRUE, 0, '2026-06-12 19:00:00+00');

SELECT setval(pg_get_serial_sequence('arbiter_bbva.policy_snapshot','id'),
              (SELECT MAX(id) FROM arbiter_bbva.policy_snapshot));

-- Cases. claim_cause 1 = 'Rotura accidental', 2 = 'Robo en vía pública'.
INSERT INTO arbiter_bbva.cases
    (id, occurred_at, reported_at, police_report_at, response_deadline, description,
     was_fast_track, claimed_amount, declared_item, event_address, locality, province,
     current_status_id, analyst_id, insured_id, claim_cause_id, coverage_id, policy_id,
     policy_snapshot_id, scoring_configuration_id) VALUES
    -- 1 · deterministic Fast Track: accidental breakage, low amount, first claim → LOW.
    --     No llm_analysis row on purpose: FAST_TRACK is decided by FastTrackValidator,
    --     never by the model (decision #6).
    (1, '2026-06-14 08:30:00+00', '2026-06-14 08:34:00+00', NULL, '2026-07-14',
     'Se me cayó el celular de las manos en mi casa. Se rompió la pantalla pero el equipo funciona normalmente',
     TRUE, 285000.00, 'Samsung Galaxy S25 Ultra', 'Casa', 'CABA', 'Buenos Aires',
     2, 1, 1, 1, 1, 1, 1, 1),

    -- 2 · repeat claimant, high amount, vague narrative → HIGH.
    (2, '2026-06-10 23:00:00+00', '2026-06-11 09:05:00+00', '2026-06-11 08:00:00+00', '2026-07-11',
     'Me robaron el celular pero no me acuerdo bien dónde ni cuándo. Fue el martes o miércoles pasado, creo que cerca de Palermo',
     FALSE, 950000.00, 'iPhone 16 Pro Max', 'Palermo o Belgrano (no precisa)', 'CABA', 'Buenos Aires',
     2, 1, 2, 2, 1, 2, 2, 1),

    -- 3 · missing documentation, never scored (risk stays absent, not LOW).
    (3, '2026-06-30 00:00:00+00', '2026-06-30 10:00:00+00', NULL, '2026-07-30',
     'Estaba saliendo de la facultad y me robaron desde una moto.',
     FALSE, 120000.00, 'Samsung Galaxy A50', 'Medrano 951', 'CABA', 'Buenos Aires',
     3, NULL, 3, 2, 1, 3, 3, NULL),

    -- 4 · missing item photo, very high amount → MEDIUM.
    (4, '2026-06-12 18:30:00+00', '2026-06-12 18:55:00+00', NULL, '2026-07-12',
     'El celular desapareció pero no sé si me lo robaron o lo perdí. No estoy seguro qué pasó exactamente',
     FALSE, 1200000.00, 'iPhone 16 Pro', 'Colectivo línea 159', 'CABA', 'Buenos Aires',
     3, NULL, 1, 2, 1, 1, 4, 1),

    -- 5 · just submitted: no classification, no score, no snapshot yet.
    (5, '2026-06-12 18:30:00+00', '2026-07-31 12:00:00+00', NULL, '2026-08-30',
     'El celular desapareció pero no sé si me lo robaron o lo perdí. No estoy seguro qué pasó exactamente',
     FALSE, 1200000.00, 'iPhone 16 Pro', 'Colectivo línea 159', 'CABA', 'Buenos Aires',
     1, NULL, 1, 2, 1, 1, NULL, NULL);

SELECT setval(pg_get_serial_sequence('arbiter_bbva.cases','id'),
              (SELECT MAX(id) FROM arbiter_bbva.cases));

INSERT INTO arbiter_bbva.llm_analysis (id, recommendation, model, prompt_version, confidence,
                                       latency_ms, analyzed_at, case_id) VALUES
    (1, 'LLM_NO_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.950, 5120, '2026-06-11 09:12:00+00', 2),
    (2, 'FALTA_DOCUMENTACION',       'qwen3-vl', 'classification-v1', 1.000, 3480, '2026-06-30 10:08:00+00', 3),
    (3, 'FALTA_DOCUMENTACION',       'qwen3-vl', 'classification-v1', 1.000, 3610, '2026-06-12 19:02:00+00', 4);

SELECT setval(pg_get_serial_sequence('arbiter_bbva.llm_analysis','id'),
              (SELECT MAX(id) FROM arbiter_bbva.llm_analysis));

-- One row per reason instead of a serialized blob — this is the "factores" the
-- Disposición SSN 2/2023 requires alongside every classification.
INSERT INTO arbiter_bbva.llm_reason (reason, analysis_id) VALUES
    ('Más de 2 siniestros en los últimos 12 meses: el asegurado tiene 3 previos (Nov 2025, Feb 2026 y Abr 2026)', 1),
    ('La descripción del incidente presenta inconsistencias con el reporte policial', 1),
    ('El asegurado no precisa lugar ni fecha del hecho', 1),
    ('Falta documento requerido: DENUNCIA_POLICIAL', 2),
    ('Falta documento requerido: FOTO_BIEN', 2),
    ('Falta documento requerido: FOTO_BIEN', 3);

-- Risk analyses. Weights come from the seeded H0012 config: amount_ratio 0.45,
-- claim_frequency 0.35, policy_standing 0.20.
INSERT INTO arbiter_bbva.risk_analysis (id, risk_score, risk_band, risk_breakdown, analyzed_at, case_id) VALUES
    (1, 0.099, 'LOW',
     '[{"factorId":"amount_ratio","rawScore":0.2192,"weight":0.45,"weightedContribution":0.0986,"rationale":"Monto reclamado es 22% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-06-14 08:36:00+00', 1),
    (2, 0.706, 'HIGH',
     '[{"factorId":"amount_ratio","rawScore":0.7917,"weight":0.45,"weightedContribution":0.3563,"rationale":"Monto reclamado es 79% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":1.0,"weight":0.35,"weightedContribution":0.35,"rationale":"Siniestros previos del asegurado: 3"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-06-11 09:11:00+00', 2),
    (3, 0.415, 'MEDIUM',
     '[{"factorId":"amount_ratio","rawScore":0.9231,"weight":0.45,"weightedContribution":0.4154,"rationale":"Monto reclamado es 92% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-06-12 19:01:00+00', 4);

SELECT setval(pg_get_serial_sequence('arbiter_bbva.risk_analysis','id'),
              (SELECT MAX(id) FROM arbiter_bbva.risk_analysis));

-- Rule evaluations behind case 1's Fast Track and case 2's block.
INSERT INTO arbiter_bbva.rule_result (rule_type, result, evaluated_value, score_contribution,
                                      evaluated_at, rule_id, case_id) VALUES
    ('FAST_TRACK', 'CUMPLE',    '0.219', 0.0986, '2026-06-14 08:35:30+00', 1, 1),
    ('FAST_TRACK', 'CUMPLE',    'AL_DIA', 0.0000, '2026-06-14 08:35:31+00', 2, 1),
    ('FAST_TRACK', 'NO_CUMPLE', '0.792', 0.3563, '2026-06-11 09:10:30+00', 1, 2);

-- actor: who drove each transition. SYSTEM rows have no changed_by — an automated
-- transition has no user behind it, which is exactly why actor can't be derived from it.
INSERT INTO arbiter_bbva.case_status_history (reason, observation, actor, changed_at, changed_by,
                                              initial_status_id, final_status_id, case_id) VALUES
    ('Denuncia registrada', NULL, 'INSURED', '2026-06-14 08:34:00+00', 1, NULL, 1, 1),
    ('Fast Track determinístico: cumple todas las reglas', 'Monto 21.9% de la suma asegurada',
     'SYSTEM', '2026-06-14 08:36:00+00', NULL, 1, 2, 1),
    ('Denuncia registrada', NULL, 'INSURED', '2026-06-11 09:05:00+00', 5, NULL, 1, 2),
    ('Clasificación disponible', 'Riesgo ALTO por reincidencia', 'SYSTEM', '2026-06-11 09:12:00+00', NULL, 1, 2, 2),
    ('Denuncia registrada', NULL, 'INSURED', '2026-06-30 10:00:00+00', 6, NULL, 1, 3),
    ('Faltan documentos obligatorios de la agenda', 'DENUNCIA_POLICIAL, FOTO_BIEN',
     'SYSTEM', '2026-06-30 10:08:00+00', NULL, 1, 3, 3),
    ('Denuncia registrada', NULL, 'INSURED', '2026-06-12 18:55:00+00', 1, NULL, 1, 4),
    ('Falta la foto del bien', NULL, 'SYSTEM', '2026-06-12 19:02:00+00', NULL, 1, 3, 4),
    ('Denuncia registrada', NULL, 'INSURED', '2026-07-31 12:00:00+00', 1, NULL, 1, 5);

-- =============================================================================
-- PART 5 — Arbiter tenant: Provincia
-- =============================================================================

-- Provincia sells Tecnología Portátil, so it needs a coverage BBVA does not have.
INSERT INTO arbiter_provincia.coverage (id, name, description, report_deadline_hours, max_events_per_year,
                                        covers_family_group, deductible, claim_exhausts_coverage,
                                        is_individual, waiting_period_days, branch_id) VALUES
    (3, 'Daño accidental', 'Cobertura por daño accidental de equipo portátil', 96, 2, FALSE, 10.00, FALSE, TRUE, 30, 3);

SELECT setval(pg_get_serial_sequence('arbiter_provincia.coverage','id'),
              (SELECT MAX(id) FROM arbiter_provincia.coverage));

INSERT INTO arbiter_provincia.insured (id, name, surname, dni, email, phone, case_count, pep, user_id) VALUES
    (1, 'Carla',    'Gómez',   '35.111.222', 'carla.gomez@example.com',      '11-5555-0004', 1, FALSE, 10),
    (2, 'Camila',   'Duarte',  '33.777.222', 'camila.duarte@example.com',    '11-5555-0008', 0, FALSE, 11),
    (3, 'Federico', 'Aguirre', '28.444.666', 'federico.aguirre@example.com', '11-5555-0009', 1, TRUE,  12),
    -- Same person as arbiter_bbva.insured(1) — one identity, two tenants, no shared row.
    (4, 'Martina',  'Soteras', '42.987.654', 'martina.soteras@example.com',  '11-5555-0001', 0, FALSE, 1);

SELECT setval(pg_get_serial_sequence('arbiter_provincia.insured','id'),
              (SELECT MAX(id) FROM arbiter_provincia.insured));

INSERT INTO arbiter_provincia.policy (id, external_policy_number, product, sum_insured, in_force, insured_id, coverage_id) VALUES
    (1, 'POL-TEC-2026-311', 'Seguro de Tecnología Portátil',  90000.00, TRUE, 1, 3),
    (2, 'POL-TEC-2026-402', 'Seguro de Tecnología Portátil', 250000.00, TRUE, 2, 3),
    (3, 'POL-CEL-2026-501', 'Celular Protegido',             900000.00, TRUE, 3, 1),
    (4, 'POL-CEL-2026-777', 'Celular Protegido',             700000.00, TRUE, 4, 1);

SELECT setval(pg_get_serial_sequence('arbiter_provincia.policy','id'),
              (SELECT MAX(id) FROM arbiter_provincia.policy));

INSERT INTO arbiter_provincia.policy_snapshot (id, external_policy_number, sum_insured, in_force,
                                               payments_up_to_date, previous_claims, queried_at) VALUES
    (1, 'POL-TEC-2026-311',  90000.00, TRUE, TRUE,  0, '2026-05-20 14:05:00+00'),
    -- Federico: in arrears and with history — policy_standing and claim_frequency both fire.
    (2, 'POL-CEL-2026-501', 900000.00, TRUE, FALSE, 2, '2026-07-05 11:20:00+00');

SELECT setval(pg_get_serial_sequence('arbiter_provincia.policy_snapshot','id'),
              (SELECT MAX(id) FROM arbiter_provincia.policy_snapshot));

-- claim_cause 6 = 'Daño accidental' (Tecnología Portátil), 2 = 'Robo en vía pública'.
INSERT INTO arbiter_provincia.cases
    (id, occurred_at, reported_at, police_report_at, response_deadline, description,
     was_fast_track, claimed_amount, declared_item, event_address, locality, province,
     current_status_id, analyst_id, insured_id, claim_cause_id, coverage_id, policy_id,
     policy_snapshot_id, scoring_configuration_id) VALUES
    -- 1 · resolved: the analyst approved it. The only closed case in the fixtures —
    --     it is what makes the full human-in-the-loop lifecycle visible in the UI.
    (1, '2026-05-19 20:15:00+00', '2026-05-20 14:00:00+00', NULL, '2026-06-19',
     'Se me cayó la notebook del escritorio y se rompió la pantalla. Enciende pero no se ve nada',
     FALSE, 38000.00, 'MacBook Air M3 15"', 'Av. Rivadavia 4820', 'CABA', 'Buenos Aires',
     5, 1, 1, 6, 3, 1, 1, 1),

    -- 2 · in arrears + repeat claimant → HIGH, waiting for the analyst.
    (2, '2026-07-04 22:40:00+00', '2026-07-05 11:15:00+00', '2026-07-05 09:30:00+00', '2026-08-04',
     'Me arrebataron el celular en la parada del colectivo, dos personas en moto',
     FALSE, 760000.00, 'Samsung Galaxy S23', 'Av. San Martín 2100', 'San Martín', 'Buenos Aires',
     2, 1, 3, 2, 1, 3, 2, 1);

SELECT setval(pg_get_serial_sequence('arbiter_provincia.cases','id'),
              (SELECT MAX(id) FROM arbiter_provincia.cases));

INSERT INTO arbiter_provincia.llm_analysis (id, recommendation, model, prompt_version, confidence,
                                            latency_ms, analyzed_at, case_id) VALUES
    (1, 'LLM_RECOMIENDA_APROBAR',    'qwen3-vl', 'classification-v1', 0.910, 4050, '2026-05-20 14:06:00+00', 1),
    (2, 'LLM_SOLICITA_REVISION_MANUAL', 'qwen3-vl', 'classification-v1', 0.620, 6240, '2026-07-05 11:22:00+00', 2);

SELECT setval(pg_get_serial_sequence('arbiter_provincia.llm_analysis','id'),
              (SELECT MAX(id) FROM arbiter_provincia.llm_analysis));

INSERT INTO arbiter_provincia.llm_reason (reason, analysis_id) VALUES
    ('La descripción es coherente con el daño visible en las fotos adjuntas', 1),
    ('Monto reclamado (42% de la suma asegurada) dentro de lo esperable para el daño descripto', 1),
    ('El asegurado no registra siniestros previos', 1),
    ('La póliza registra cuotas impagas al momento del hecho', 2),
    ('El asegurado tiene 2 siniestros previos por la misma causa', 2),
    ('La narrativa es consistente, pero el perfil de riesgo amerita revisión humana', 2);

INSERT INTO arbiter_provincia.risk_analysis (id, risk_score, risk_band, risk_breakdown, analyzed_at, case_id) VALUES
    (1, 0.190, 'LOW',
     '[{"factorId":"amount_ratio","rawScore":0.4222,"weight":0.45,"weightedContribution":0.19,"rationale":"Monto reclamado es 42% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-05-20 14:07:00+00', 1),
    (2, 0.813, 'CRITICAL',
     '[{"factorId":"amount_ratio","rawScore":0.8444,"weight":0.45,"weightedContribution":0.38,"rationale":"Monto reclamado es 84% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.6667,"weight":0.35,"weightedContribution":0.2333,"rationale":"Siniestros previos del asegurado: 2"},{"factorId":"policy_standing","rawScore":1.0,"weight":0.2,"weightedContribution":0.2,"rationale":"La póliza registra cuotas impagas"}]'::jsonb,
     '2026-07-05 11:21:00+00', 2);

SELECT setval(pg_get_serial_sequence('arbiter_provincia.risk_analysis','id'),
              (SELECT MAX(id) FROM arbiter_provincia.risk_analysis));

-- The analyst's decision on case 1. Human-in-the-loop: the model recommended, the
-- analyst decided, and only then did the case reach a final state.
INSERT INTO arbiter_provincia.case_classification (id, decision, analyst_justification, decided_at,
                                                   classification_attempts, llm_analysis_id, analyst_id) VALUES
    (1, 'APROBAR', 'Documentación completa y daño consistente con lo declarado. Coincido con la recomendación del modelo.',
     '2026-05-21 10:30:00+00', 1, 1, 1);

SELECT setval(pg_get_serial_sequence('arbiter_provincia.case_classification','id'),
              (SELECT MAX(id) FROM arbiter_provincia.case_classification));

UPDATE arbiter_provincia.cases SET classification_id = 1 WHERE id = 1;

INSERT INTO arbiter_provincia.rule_result (rule_type, result, evaluated_value, score_contribution,
                                           evaluated_at, rule_id, case_id) VALUES
    ('FAST_TRACK', 'NO_CUMPLE', '0.844',  0.3800, '2026-07-05 11:20:30+00', 1, 2),
    ('FAST_TRACK', 'NO_CUMPLE', 'SUSPENDIDA', 0.2000, '2026-07-05 11:20:31+00', 2, 2);

INSERT INTO arbiter_provincia.case_status_history (reason, observation, actor, changed_at, changed_by,
                                                   initial_status_id, final_status_id, case_id) VALUES
    ('Denuncia registrada', NULL, 'INSURED', '2026-05-20 14:00:00+00', 10, NULL, 1, 1),
    ('Clasificación disponible', 'El modelo recomienda aprobar', 'SYSTEM', '2026-05-20 14:07:00+00', NULL, 1, 2, 1),
    -- The only ANALYST transition in the fixtures: human-in-the-loop closing a case.
    ('El analista aprobó el siniestro', 'Documentación completa', 'ANALYST', '2026-05-21 10:30:00+00', 4, 2, 5, 1),
    ('Denuncia registrada', NULL, 'INSURED', '2026-07-05 11:15:00+00', 12, NULL, 1, 2),
    ('Clasificación disponible', 'Riesgo CRÍTICO: mora + reincidencia', 'SYSTEM', '2026-07-05 11:22:00+00', NULL, 1, 2, 2);

-- Notification sent to Carla when her case was approved.
INSERT INTO arbiter_provincia.notification (type, channel, content, sent, read, sent_at, read_at,
                                            recipient_id, case_id) VALUES
    ('CAMBIO_ESTADO', 'EMAIL',
     'Tu siniestro fue aprobado. En los próximos días vas a recibir el detalle de la liquidación.',
     TRUE, TRUE, '2026-05-21 10:31:00+00', '2026-05-21 18:02:00+00', 10, 1);

COMMIT;
