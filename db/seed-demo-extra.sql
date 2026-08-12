-- =============================================================================
-- Arbiter — datos extra de demo (INCREMENTAL, no destructivo)
--
-- Aplica MÁS pólizas, siniestros y clasificaciones SOBRE una BD ya sembrada con
-- db/init-multitenant.sql + db/seed-demo.sql (estado canónico). No borra nada:
-- continúa las secuencias desde los ids que deja seed-demo.sql. Si el equipo ya
-- corrió un reseed con la versión ampliada de seed-demo.sql, NO correr esto (los
-- ids ya existirían). Pensado para poblar Railway sin el trío destructivo.
--
-- Uso:  psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f db/seed-demo-extra.sql
-- =============================================================================

BEGIN;

-- =============================================================================
-- PART 6 — Volumen extra para la demo (generado, ver scratchpad/gen_seed.py)
--
-- Mantiene la restricción del header: los asegurados son sólo personas con login
-- real. Martina (user 1) y Julián (user 5) ya lo tienen; acá Julián se suma como
-- segundo asegurado de Provincia (no crea usuarios nuevos, no ensucia Usuarios).
-- Los montos y bandas de riesgo se calculan con la fórmula H0012 documentada arriba.
-- =============================================================================

-- Julián pasa a ser cliente de Provincia además de BBVA: un user, dos insured en
-- distintos tenants — el mismo patrón que ya demuestra Martina, ahora en el otro sentido.
INSERT INTO arbiter_common.user_insurer (user_id, insurer_id) VALUES (5, 2);

INSERT INTO aseguradora_provincia.asegurado (id, documento, cuil, nombre, apellido, email, telefono) VALUES
    (2, '30.555.777', '20-30555777-3', 'Julián', 'Pérez', 'asegurado2.arbiter@gmail.com', '11-5555-0002');
SELECT setval(pg_get_serial_sequence('aseguradora_provincia.asegurado','id'),
              (SELECT MAX(id) FROM aseguradora_provincia.asegurado));

INSERT INTO arbiter_provincia.insured (id, name, surname, dni, email, phone, case_count, pep, user_id) VALUES
    (2, 'Julián', 'Pérez', '30.555.777', 'asegurado2.arbiter@gmail.com', '11-5555-0002', 0, FALSE, 5);
SELECT setval(pg_get_serial_sequence('arbiter_provincia.insured','id'),
              (SELECT MAX(id) FROM arbiter_provincia.insured));

-- ─── BD Aseguradora BBVA: pólizas nuevas ────────────────────────────────────
INSERT INTO aseguradora_bbva.poliza (id, numero, nro_certificado, titular_id, rama, producto, bien_asegurado,
                                     imei, vigencia_desde, vigencia_hasta, estado_contrato, estado_pago,
                                     cuotas_pagas, cuotas_impagas, saldo_deuda, forma_pago, cubre_grupo_familiar) VALUES
    (6, 'POL-CEL-2024-010', '621301', 1, 'Celulares', 'Celular Protegido Premium', 'iPhone 13', '351000000000010', '2024-03-01','2025-03-01','ACTIVA','AL_DIA', 12, 0, 0.00, 'TARJETA DE CREDITO', TRUE),
    (7, 'POL-CEL-2024-055', '621302', 2, 'Celulares', 'Celular Protegido Básico', 'Xiaomi Redmi Note 12', '352000000000055', '2024-06-01','2025-06-01','ACTIVA','AL_DIA', 12, 0, 0.00, 'DEBITO', TRUE),
    (8, 'POL-CEL-2025-140', '621303', 1, 'Celulares', 'Celular Protegido Premium', 'Samsung Galaxy S23 Ultra', '353000000000140', '2025-05-01','2026-05-01','ACTIVA','AL_DIA', 9, 0, 0.00, 'TARJETA DE CREDITO', TRUE),
    (9, 'POL-CEL-2025-201', '621304', 2, 'Celulares', 'Celular Protegido Premium', 'iPhone 14 Pro', '354000000000201', '2025-08-01','2026-08-01','ACTIVA','SUSPENDIDA', 6, 3, 34500.00, 'DEBITO', FALSE),
    (10, 'POL-CEL-2026-260', '621305', 1, 'Celulares', 'Celular Protegido Básico', 'Motorola Edge 40', '355000000000260', '2026-03-01','2027-03-01','ACTIVA','AL_DIA', 5, 0, 0.00, 'TARJETA DE CREDITO', FALSE);
SELECT setval(pg_get_serial_sequence('aseguradora_bbva.poliza','id'),
              (SELECT MAX(id) FROM aseguradora_bbva.poliza));

INSERT INTO aseguradora_bbva.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct) VALUES
    (6, 1, 'Robo de celular', 900000.00, 10.00),
    (6, 2, 'Hurto', 360000.00, 15.00),
    (7, 1, 'Robo de celular', 300000.00, 10.00),
    (7, 2, 'Hurto', 120000.00, 15.00),
    (8, 1, 'Robo de celular', 1400000.00, 10.00),
    (8, 2, 'Hurto', 560000.00, 15.00),
    (9, 1, 'Robo de celular', 1100000.00, 10.00),
    (9, 2, 'Hurto', 440000.00, 15.00),
    (10, 1, 'Robo de celular', 500000.00, 10.00),
    (10, 2, 'Hurto', 200000.00, 15.00);

-- ─── BD Aseguradora Provincia: pólizas nuevas ───────────────────────────────
INSERT INTO aseguradora_provincia.poliza (id, numero, nro_certificado, titular_id, rama, producto, bien_asegurado,
                                          imei, vigencia_desde, vigencia_hasta, estado_contrato, estado_pago,
                                          cuotas_pagas, cuotas_impagas, saldo_deuda, forma_pago,
                                          max_eventos_anuales, segundo_evento_pct, cubre_grupo_familiar, datos_proveedor) VALUES
    (4, 'POL-CEL-2025-820', '700901', 2, 'Celulares', 'Celular Protegido', 'iPhone 13 Mini', '357000000000820', '2025-07-01','2026-07-01','ACTIVA','AL_DIA', 10, 0, 0.00, 'TARJETA DE CREDITO', NULL, NULL, FALSE, NULL),
    (5, 'POL-CEL-2026-905', '700902', 2, 'Celulares', 'Celular Protegido', 'Samsung Galaxy A34', '358000000000905', '2026-02-01','2027-02-01','ACTIVA','SUSPENDIDA', 4, 2, 18200.00, 'DEBITO', NULL, NULL, FALSE, NULL),
    (6, 'POL-TEC-2025-410', '700903', 1, 'Tecnología Portátil', 'Seguro de Tecnología Portátil', 'Dell XPS 13', NULL, '2025-04-01','2026-04-01','ACTIVA','AL_DIA', 12, 0, 0.00, 'TARJETA DE CREDITO', NULL, NULL, FALSE, NULL),
    (7, 'POL-CEL-2026-980', '700904', 1, 'Celulares', 'Celular Protegido', 'iPhone 15 Pro Max', '359000000000980', '2026-04-01','2027-04-01','ACTIVA','AL_DIA', 5, 0, 0.00, 'TARJETA DE CREDITO', NULL, NULL, FALSE, NULL);
SELECT setval(pg_get_serial_sequence('aseguradora_provincia.poliza','id'),
              (SELECT MAX(id) FROM aseguradora_provincia.poliza));

INSERT INTO aseguradora_provincia.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct) VALUES
    (4, 1, 'Robo de celular', 800000.00, 10.00),
    (5, 1, 'Robo de celular', 450000.00, 10.00),
    (6, 1, 'Daño accidental', 120000.00, 10.00),
    (7, 1, 'Robo de celular', 1600000.00, 10.00);

-- ─── Snapshots locales de las pólizas nuevas (arbiter tenant) ────────────────
INSERT INTO arbiter_bbva.policy (id, external_policy_number, product, sum_insured, in_force, insured_id, coverage_id) VALUES
    (6, 'POL-CEL-2024-010', 'Celular Protegido Premium', 900000.00, TRUE, 1, 1),
    (7, 'POL-CEL-2024-055', 'Celular Protegido Básico', 300000.00, TRUE, 2, 1),
    (8, 'POL-CEL-2025-140', 'Celular Protegido Premium', 1400000.00, TRUE, 1, 1),
    (9, 'POL-CEL-2025-201', 'Celular Protegido Premium', 1100000.00, TRUE, 2, 1),
    (10, 'POL-CEL-2026-260', 'Celular Protegido Básico', 500000.00, TRUE, 1, 1);
SELECT setval(pg_get_serial_sequence('arbiter_bbva.policy','id'),
              (SELECT MAX(id) FROM arbiter_bbva.policy));

INSERT INTO arbiter_provincia.policy (id, external_policy_number, product, sum_insured, in_force, insured_id, coverage_id) VALUES
    (4, 'POL-CEL-2025-820', 'Celular Protegido', 800000.00, TRUE, 2, 1),
    (5, 'POL-CEL-2026-905', 'Celular Protegido', 450000.00, TRUE, 2, 1),
    (6, 'POL-TEC-2025-410', 'Seguro de Tecnología Portátil', 120000.00, TRUE, 1, 3),
    (7, 'POL-CEL-2026-980', 'Celular Protegido', 1600000.00, TRUE, 1, 1);
SELECT setval(pg_get_serial_sequence('arbiter_provincia.policy','id'),
              (SELECT MAX(id) FROM arbiter_provincia.policy));

-- ─── arbiter_bbva: casos nuevos ──────────────────────────────────────────────
INSERT INTO arbiter_bbva.policy_snapshot (id, external_policy_number, sum_insured, in_force,
                                      payments_up_to_date, previous_claims, queried_at) VALUES
    (5, 'POL-CEL-2024-010', 900000.00, TRUE, TRUE, 0, '2024-04-11 08:55:00+00'),
    (6, 'POL-CEL-2024-055', 300000.00, TRUE, TRUE, 0, '2024-08-02 20:05:00+00'),
    (7, 'POL-CEL-2025-140', 1400000.00, TRUE, TRUE, 1, '2025-06-16 09:55:00+00'),
    (8, 'POL-CEL-2025-201', 1100000.00, TRUE, FALSE, 2, '2026-07-21 08:25:00+00'),
    (9, 'POL-CEL-2025-140', 1400000.00, TRUE, TRUE, 1, '2026-07-29 08:55:00+00'),
    (10, 'POL-CEL-2026-260', 500000.00, TRUE, TRUE, 0, '2026-08-01 18:15:00+00'),
    (11, 'POL-CEL-2024-010', 900000.00, TRUE, TRUE, 0, '2026-08-03 12:10:00+00'),
    (12, 'POL-CEL-2026-042', 1300000.00, TRUE, TRUE, 0, '2026-08-05 08:40:00+00'),
    (13, 'POL-CEL-2025-099', 1200000.00, TRUE, TRUE, 3, '2026-07-11 10:55:00+00'),
    (14, 'POL-CEL-2026-260', 500000.00, TRUE, TRUE, 0, '2026-08-02 15:55:00+00'),
    (15, 'POL-CEL-2026-118', 800000.00, TRUE, TRUE, 0, '2026-08-09 08:55:00+00');
SELECT setval(pg_get_serial_sequence('arbiter_bbva.policy_snapshot','id'), (SELECT MAX(id) FROM arbiter_bbva.policy_snapshot));

INSERT INTO arbiter_bbva.cases
    (id, occurred_at, reported_at, police_report_at, response_deadline, description,
     was_fast_track, claimed_amount, declared_item, event_address, locality, province,
     current_status_id, analyst_id, insured_id, claim_cause_id, coverage_id, policy_id,
     policy_snapshot_id, scoring_configuration_id) VALUES
    (6, '2024-04-10 21:00:00+00', '2024-04-11 09:00:00+00', '2024-04-11 08:00:00+00', '2024-05-11',
     'Me robaron el celular a la salida del subte, dos personas me rodearon y me lo sacaron de la mano.',
     FALSE, 380000.00, 'iPhone 13', 'Av. Corrientes 3200', 'CABA', 'Buenos Aires',
     5, 1, 1, 2, 1, 6, 5, 1),
    (7, '2024-08-02 19:30:00+00', '2024-08-02 20:10:00+00', '2024-08-02 21:00:00+00', '2024-09-01',
     'Arrebato en la parada del colectivo, me empujaron y se llevaron el teléfono.',
     FALSE, 250000.00, 'Xiaomi Redmi Note 12', 'Av. Rivadavia 8800', 'CABA', 'Buenos Aires',
     5, 2, 2, 2, 1, 7, 6, 1),
    (8, '2025-06-15 14:00:00+00', '2025-06-16 10:00:00+00', NULL, '2025-07-16',
     'Dejé el celular sobre la mesa de un bar y cuando volví no estaba.',
     FALSE, 700000.00, 'Samsung Galaxy S23 Ultra', 'Palermo', 'CABA', 'Buenos Aires',
     6, 1, 1, 3, 1, 8, 7, 1),
    (9, '2026-07-20 23:15:00+00', '2026-07-21 08:30:00+00', '2026-07-21 07:00:00+00', '2026-08-20',
     'Me robaron el celular cerca de la cancha, había mucha gente y no vi bien a quién fue.',
     FALSE, 980000.00, 'iPhone 14 Pro', 'Av. Juan B. Justo 200', 'CABA', 'Buenos Aires',
     2, 1, 2, 2, 1, 9, 8, 1),
    (10, '2026-07-28 20:00:00+00', '2026-07-29 09:00:00+00', '2026-07-29 08:15:00+00', '2026-08-28',
     'Salía del trabajo y me robaron el celular con un arma, en la esquina de la oficina.',
     FALSE, 1300000.00, 'Samsung Galaxy S23 Ultra', 'Microcentro', 'CABA', 'Buenos Aires',
     2, 2, 1, 2, 1, 8, 9, 1),
    (11, '2026-08-01 18:00:00+00', '2026-08-01 18:20:00+00', NULL, '2026-08-31',
     'Me sacaron el celular de la mochila en el tren, me di cuenta al bajar.',
     TRUE, 470000.00, 'Motorola Edge 40', 'Estación Once', 'CABA', 'Buenos Aires',
     2, NULL, 1, 2, 1, 10, 10, 1),
    (12, '2026-08-03 12:00:00+00', '2026-08-03 12:15:00+00', NULL, '2026-09-02',
     'Se me cayó el celular y se rompió la pantalla, funciona pero no se ve bien.',
     TRUE, 180000.00, 'iPhone 13', 'Casa', 'CABA', 'Buenos Aires',
     2, NULL, 1, 1, 1, 6, 11, 1),
    (13, '2026-08-05 09:00:00+00', '2026-08-05 10:00:00+00', NULL, '2026-09-04',
     'Me robaron el celular en el barrio, todavía tengo que hacer la denuncia policial.',
     FALSE, 290000.00, 'Xiaomi Redmi Note 12', 'Flores', 'CABA', 'Buenos Aires',
     3, NULL, 2, 2, 1, 7, NULL, NULL),
    (14, '2026-08-06 21:00:00+00', '2026-08-07 08:00:00+00', NULL, '2026-09-06',
     'Robo en la vía pública, adjunto fotos pero me falta el comprobante de compra.',
     FALSE, 1050000.00, 'iPhone 14 Pro', 'Belgrano', 'CABA', 'Buenos Aires',
     3, NULL, 2, 2, 1, 9, NULL, NULL),
    (15, '2026-08-04 22:00:00+00', '2026-08-05 08:45:00+00', '2026-08-05 08:00:00+00', '2026-09-04',
     'Me robaron el celular mientras esperaba un Uber, se subieron a una moto y salieron.',
     FALSE, 620000.00, 'Samsung Galaxy A56', 'Villa Crespo', 'CABA', 'Buenos Aires',
     2, 1, 1, 2, 1, 1, 12, 1),
    (16, '2026-07-10 03:00:00+00', '2026-07-11 11:00:00+00', NULL, '2026-08-10',
     'Me robaron el celular en una fiesta, no recuerdo bien la hora ni el lugar exacto.',
     FALSE, 1150000.00, 'iPhone 15 Pro Max', 'Costanera', 'CABA', 'Buenos Aires',
     6, 2, 2, 2, 1, 2, 13, 1),
    (17, '2026-08-02 15:00:00+00', '2026-08-02 16:00:00+00', NULL, '2026-09-01',
     'Dejé el celular cargando en un local y desapareció, no vi quién lo agarró.',
     FALSE, 480000.00, 'Motorola Edge 40', 'Caballito', 'CABA', 'Buenos Aires',
     6, 1, 1, 3, 1, 10, 14, 1),
    (18, '2026-08-08 20:30:00+00', '2026-08-09 09:00:00+00', '2026-08-09 08:30:00+00', '2026-09-08',
     'Me robaron el celular en la puerta de mi casa al llegar, dos personas en moto.',
     FALSE, 750000.00, 'Samsung Galaxy S24', 'Devoto', 'CABA', 'Buenos Aires',
     2, 2, 1, 2, 1, 4, 15, 1),
    (19, '2026-08-10 21:00:00+00', '2026-08-11 08:00:00+00', NULL, '2026-09-10',
     'Me robaron el celular saliendo del gimnasio, recién hago la denuncia.',
     FALSE, 1400000.00, 'iPhone 15', 'Núñez', 'CABA', 'Buenos Aires',
     1, NULL, 1, 2, 1, 5, NULL, NULL),
    (20, '2026-07-25 19:00:00+00', '2026-07-26 09:00:00+00', NULL, '2026-08-25',
     'Robo en la vía pública, el sistema no pudo procesar la clasificación.',
     FALSE, 190000.00, 'Motorola Moto G54', 'Once', 'CABA', 'Buenos Aires',
     4, NULL, 2, 2, 1, 3, NULL, NULL);
SELECT setval(pg_get_serial_sequence('arbiter_bbva.cases','id'), (SELECT MAX(id) FROM arbiter_bbva.cases));

INSERT INTO arbiter_bbva.llm_analysis (id, recommendation, model, prompt_version, confidence,
                                   latency_ms, analyzed_at, case_id) VALUES
    (4, 'LLM_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.900, 4800, '2024-04-11 09:07:00+00', 6),
    (5, 'LLM_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.900, 4800, '2024-08-02 20:17:00+00', 7),
    (6, 'LLM_NO_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.940, 4800, '2025-06-16 10:07:00+00', 8),
    (7, 'LLM_SOLICITA_REVISION_MANUAL', 'qwen3-vl', 'classification-v1', 0.620, 4800, '2026-07-21 08:37:00+00', 9),
    (8, 'LLM_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.900, 4800, '2026-07-29 09:07:00+00', 10),
    (9, 'FALTA_DOCUMENTACION', 'qwen3-vl', 'classification-v1', 1.000, 3200, '2026-08-05 10:07:00+00', 13),
    (10, 'FALTA_DOCUMENTACION', 'qwen3-vl', 'classification-v1', 1.000, 3200, '2026-08-07 08:07:00+00', 14),
    (11, 'LLM_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.900, 4800, '2026-08-05 08:52:00+00', 15),
    (12, 'LLM_NO_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.940, 4800, '2026-07-11 11:07:00+00', 16),
    (13, 'LLM_NO_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.940, 4800, '2026-08-02 16:07:00+00', 17),
    (14, 'LLM_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.900, 4800, '2026-08-09 09:07:00+00', 18);
SELECT setval(pg_get_serial_sequence('arbiter_bbva.llm_analysis','id'), (SELECT MAX(id) FROM arbiter_bbva.llm_analysis));

INSERT INTO arbiter_bbva.llm_reason (reason, analysis_id) VALUES
    ('La descripción es coherente con el reporte policial adjunto', 4),
    ('Monto reclamado (42% de la suma asegurada) razonable para el equipo', 4),
    ('El asegurado no registra siniestros previos', 4),
    ('Relato coherente con el horario y la zona', 5),
    ('Sin siniestros previos', 5),
    ('Monto acorde al equipo', 5),
    ('El hecho descripto es un hurto, excluido por la cobertura de robo', 6),
    ('No hay violencia ni intimidación en el relato', 6),
    ('Falta denuncia policial que respalde el hecho', 6),
    ('La póliza registra cuotas impagas al momento del hecho', 7),
    ('El asegurado tiene 2 siniestros previos', 7),
    ('El relato es genérico pero no presenta contradicciones', 7),
    ('Denuncia policial presente y consistente', 8),
    ('Monto alto pero dentro de la suma asegurada', 8),
    ('Un siniestro previo, sin patrón de reincidencia', 8),
    ('Falta documento requerido: police_report', 9),
    ('Falta documento requerido: imei_deregistration', 9),
    ('Falta documento requerido: purchase_proof', 10),
    ('Relato consistente con la denuncia policial', 11),
    ('Sin siniestros previos', 11),
    ('Monto dentro de lo esperable', 11),
    ('El asegurado acumula 3 siniestros previos en 12 meses', 12),
    ('El relato no precisa lugar ni hora del hecho', 12),
    ('Inconsistencias entre la fecha declarada y la denuncia', 12),
    ('El hecho es un hurto (sin violencia), excluido por la cobertura de robo', 13),
    ('Denuncia policial consistente', 14),
    ('Sin reincidencia', 14),
    ('Monto acorde', 14);

INSERT INTO arbiter_bbva.risk_analysis (id, risk_score, risk_band, risk_breakdown, analyzed_at, case_id) VALUES
    (4, 0.19, 'LOW',
     '[{"factorId":"amount_ratio","rawScore":0.4222,"weight":0.45,"weightedContribution":0.19,"rationale":"Monto reclamado es 42% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2024-04-11 09:06:00+00', 6),
    (5, 0.375, 'MEDIUM',
     '[{"factorId":"amount_ratio","rawScore":0.8333,"weight":0.45,"weightedContribution":0.375,"rationale":"Monto reclamado es 83% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2024-08-02 20:16:00+00', 7),
    (6, 0.342, 'MEDIUM',
     '[{"factorId":"amount_ratio","rawScore":0.5,"weight":0.45,"weightedContribution":0.225,"rationale":"Monto reclamado es 50% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.3333,"weight":0.35,"weightedContribution":0.1167,"rationale":"Siniestros previos del asegurado: 1"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2025-06-16 10:06:00+00', 8),
    (7, 0.834, 'CRITICAL',
     '[{"factorId":"amount_ratio","rawScore":0.8909,"weight":0.45,"weightedContribution":0.4009,"rationale":"Monto reclamado es 89% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.6667,"weight":0.35,"weightedContribution":0.2333,"rationale":"Siniestros previos del asegurado: 2"},{"factorId":"policy_standing","rawScore":1.0,"weight":0.2,"weightedContribution":0.2,"rationale":"La póliza registra cuotas impagas"}]'::jsonb,
     '2026-07-21 08:36:00+00', 9),
    (8, 0.535, 'MEDIUM',
     '[{"factorId":"amount_ratio","rawScore":0.9286,"weight":0.45,"weightedContribution":0.4179,"rationale":"Monto reclamado es 93% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.3333,"weight":0.35,"weightedContribution":0.1167,"rationale":"Siniestros previos del asegurado: 1"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-07-29 09:06:00+00', 10),
    (9, 0.423, 'MEDIUM',
     '[{"factorId":"amount_ratio","rawScore":0.94,"weight":0.45,"weightedContribution":0.423,"rationale":"Monto reclamado es 94% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-08-01 18:26:00+00', 11),
    (10, 0.09, 'LOW',
     '[{"factorId":"amount_ratio","rawScore":0.2,"weight":0.45,"weightedContribution":0.09,"rationale":"Monto reclamado es 20% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-08-03 12:21:00+00', 12),
    (11, 0.215, 'LOW',
     '[{"factorId":"amount_ratio","rawScore":0.4769,"weight":0.45,"weightedContribution":0.2146,"rationale":"Monto reclamado es 48% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-08-05 08:51:00+00', 15),
    (12, 0.781, 'HIGH',
     '[{"factorId":"amount_ratio","rawScore":0.9583,"weight":0.45,"weightedContribution":0.4313,"rationale":"Monto reclamado es 96% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":1.0,"weight":0.35,"weightedContribution":0.35,"rationale":"Siniestros previos del asegurado: 3"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-07-11 11:06:00+00', 16),
    (13, 0.432, 'MEDIUM',
     '[{"factorId":"amount_ratio","rawScore":0.96,"weight":0.45,"weightedContribution":0.432,"rationale":"Monto reclamado es 96% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-08-02 16:06:00+00', 17),
    (14, 0.422, 'MEDIUM',
     '[{"factorId":"amount_ratio","rawScore":0.9375,"weight":0.45,"weightedContribution":0.4219,"rationale":"Monto reclamado es 94% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-08-09 09:06:00+00', 18);
SELECT setval(pg_get_serial_sequence('arbiter_bbva.risk_analysis','id'), (SELECT MAX(id) FROM arbiter_bbva.risk_analysis));

INSERT INTO arbiter_bbva.rule_result (rule_type, result, evaluated_value, score_contribution,
                                  evaluated_at, rule_id, case_id) VALUES
    ('COVERAGE_EXCLUSION', 'NO_CUMPLE', 'Hurto', 0.0000, '2025-06-16 10:05:00+00', 3, 8),
    ('FAST_TRACK', 'CUMPLE', '0.940', 0.423, '2026-08-01 18:25:00+00', 1, 11),
    ('FAST_TRACK', 'CUMPLE', 'AL_DIA', 0.0000, '2026-08-01 18:25:00+00', 2, 11),
    ('FAST_TRACK', 'CUMPLE', '0.200', 0.09, '2026-08-03 12:20:00+00', 1, 12),
    ('FAST_TRACK', 'CUMPLE', 'AL_DIA', 0.0000, '2026-08-03 12:20:00+00', 2, 12),
    ('COVERAGE_EXCLUSION', 'NO_CUMPLE', 'Hurto', 0.0000, '2026-08-02 16:05:00+00', 3, 17);

INSERT INTO arbiter_bbva.case_classification (id, decision, analyst_justification, decided_at,
                                          classification_attempts, llm_analysis_id, analyst_id) VALUES
    (1, 'APROBAR', 'Documentación completa y relato consistente con la denuncia policial. Coincido con el modelo.',
     '2024-04-11 09:30:00+00', 1, 4, 1),
    (2, 'APROBAR', 'Caso claro, sin señales de riesgo. Aprobado.',
     '2024-08-02 20:40:00+00', 1, 5, 2),
    (3, 'RECHAZAR', 'El relato describe un descuido (hurto), no un robo. La cobertura contratada no lo cubre.',
     '2025-06-16 10:30:00+00', 1, 6, 1),
    (4, 'RECHAZAR', 'Reincidencia y relato impreciso. Se rechaza a la espera de mayor sustento.',
     '2026-07-11 11:30:00+00', 1, 12, 2),
    (5, 'RECHAZAR', 'Hurto no cubierto por la póliza de robo contratada.',
     '2026-08-02 16:30:00+00', 1, 13, 1);
SELECT setval(pg_get_serial_sequence('arbiter_bbva.case_classification','id'), (SELECT MAX(id) FROM arbiter_bbva.case_classification));

INSERT INTO arbiter_bbva.case_status_history (reason, observation, actor, changed_at, changed_by,
                                          initial_status_id, final_status_id, case_id) VALUES
    ('Denuncia registrada', NULL, 'INSURED', '2024-04-11 09:00:00+00', 1, NULL, 1, 6),
    ('Clasificación disponible', NULL, 'SYSTEM', '2024-04-11 09:07:00+00', NULL, 1, 2, 6),
    ('El analista aprobó el siniestro', NULL, 'ANALYST', '2024-04-11 09:30:00+00', 2, 2, 5, 6),
    ('Denuncia registrada', NULL, 'INSURED', '2024-08-02 20:10:00+00', 5, NULL, 1, 7),
    ('Clasificación disponible', NULL, 'SYSTEM', '2024-08-02 20:17:00+00', NULL, 1, 2, 7),
    ('El analista aprobó el siniestro', NULL, 'ANALYST', '2024-08-02 20:40:00+00', 8, 2, 5, 7),
    ('Denuncia registrada', NULL, 'INSURED', '2025-06-16 10:00:00+00', 1, NULL, 1, 8),
    ('Clasificación disponible', NULL, 'SYSTEM', '2025-06-16 10:07:00+00', NULL, 1, 2, 8),
    ('El analista rechazó el siniestro', NULL, 'ANALYST', '2025-06-16 10:30:00+00', 2, 2, 6, 8),
    ('Denuncia registrada', NULL, 'INSURED', '2026-07-21 08:30:00+00', 5, NULL, 1, 9),
    ('Clasificación disponible', NULL, 'SYSTEM', '2026-07-21 08:37:00+00', NULL, 1, 2, 9),
    ('Denuncia registrada', NULL, 'INSURED', '2026-07-29 09:00:00+00', 1, NULL, 1, 10),
    ('Clasificación disponible', NULL, 'SYSTEM', '2026-07-29 09:07:00+00', NULL, 1, 2, 10),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-01 18:20:00+00', 1, NULL, 1, 11),
    ('Fast Track determinístico: cumple todas las reglas', NULL, 'SYSTEM', '2026-08-01 18:26:00+00', NULL, 1, 2, 11),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-03 12:15:00+00', 1, NULL, 1, 12),
    ('Fast Track determinístico: cumple todas las reglas', NULL, 'SYSTEM', '2026-08-03 12:21:00+00', NULL, 1, 2, 12),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-05 10:00:00+00', 5, NULL, 1, 13),
    ('Faltan documentos obligatorios de la agenda', NULL, 'SYSTEM', '2026-08-05 10:07:00+00', NULL, 1, 3, 13),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-07 08:00:00+00', 5, NULL, 1, 14),
    ('Faltan documentos obligatorios de la agenda', NULL, 'SYSTEM', '2026-08-07 08:07:00+00', NULL, 1, 3, 14),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-05 08:45:00+00', 1, NULL, 1, 15),
    ('Clasificación disponible', NULL, 'SYSTEM', '2026-08-05 08:52:00+00', NULL, 1, 2, 15),
    ('Denuncia registrada', NULL, 'INSURED', '2026-07-11 11:00:00+00', 5, NULL, 1, 16),
    ('Clasificación disponible', NULL, 'SYSTEM', '2026-07-11 11:07:00+00', NULL, 1, 2, 16),
    ('El analista rechazó el siniestro', NULL, 'ANALYST', '2026-07-11 11:30:00+00', 8, 2, 6, 16),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-02 16:00:00+00', 1, NULL, 1, 17),
    ('Clasificación disponible', NULL, 'SYSTEM', '2026-08-02 16:07:00+00', NULL, 1, 2, 17),
    ('El analista rechazó el siniestro', NULL, 'ANALYST', '2026-08-02 16:30:00+00', 2, 2, 6, 17),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-09 09:00:00+00', 1, NULL, 1, 18),
    ('Clasificación disponible', NULL, 'SYSTEM', '2026-08-09 09:07:00+00', NULL, 1, 2, 18),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-11 08:00:00+00', 1, NULL, 1, 19),
    ('Denuncia registrada', NULL, 'INSURED', '2026-07-26 09:00:00+00', 5, NULL, 1, 20),
    ('La clasificación falló tras agotar reintentos', NULL, 'SYSTEM', '2026-07-26 09:06:00+00', NULL, 1, 4, 20);

INSERT INTO arbiter_bbva.notification (type, channel, content, sent, read, sent_at, read_at,
                                   recipient_id, case_id) VALUES
    ('CAMBIO_ESTADO', 'EMAIL', 'Tu siniestro fue aprobado. En los próximos días vas a recibir el detalle de la liquidación.', TRUE, FALSE, '2024-04-11 09:30:00+00', NULL, 1, 6),
    ('CAMBIO_ESTADO', 'EMAIL', 'Tu siniestro fue aprobado. En los próximos días vas a recibir el detalle de la liquidación.', TRUE, FALSE, '2024-08-02 20:40:00+00', NULL, 2, 7),
    ('CAMBIO_ESTADO', 'EMAIL', 'Tu siniestro fue rechazado. Podés ver el detalle y los motivos en el portal.', TRUE, FALSE, '2025-06-16 10:30:00+00', NULL, 1, 8),
    ('CAMBIO_ESTADO', 'EMAIL', 'Tu siniestro fue rechazado. Podés ver el detalle y los motivos en el portal.', TRUE, FALSE, '2026-07-11 11:30:00+00', NULL, 2, 16),
    ('CAMBIO_ESTADO', 'EMAIL', 'Tu siniestro fue rechazado. Podés ver el detalle y los motivos en el portal.', TRUE, FALSE, '2026-08-02 16:30:00+00', NULL, 1, 17);

-- ─── arbiter_provincia: casos nuevos ──────────────────────────────────────────────
INSERT INTO arbiter_provincia.policy_snapshot (id, external_policy_number, sum_insured, in_force,
                                      payments_up_to_date, previous_claims, queried_at) VALUES
    (3, 'POL-CEL-2025-820', 800000.00, TRUE, TRUE, 0, '2025-08-15 09:25:00+00'),
    (4, 'POL-CEL-2025-820', 800000.00, TRUE, TRUE, 1, '2026-08-02 08:55:00+00'),
    (5, 'POL-CEL-2026-905', 450000.00, TRUE, FALSE, 1, '2026-08-06 08:25:00+00'),
    (6, 'POL-TEC-2025-410', 120000.00, TRUE, TRUE, 0, '2025-09-11 08:55:00+00'),
    (7, 'POL-CEL-2026-980', 1600000.00, TRUE, TRUE, 0, '2026-08-10 08:25:00+00'),
    (8, 'POL-CEL-2026-501', 900000.00, TRUE, FALSE, 2, '2026-07-16 11:55:00+00'),
    (9, 'POL-CEL-2026-777', 700000.00, TRUE, TRUE, 0, '2026-08-07 08:55:00+00'),
    (10, 'POL-TEC-2025-410', 120000.00, TRUE, TRUE, 0, '2026-08-04 11:15:00+00');
SELECT setval(pg_get_serial_sequence('arbiter_provincia.policy_snapshot','id'), (SELECT MAX(id) FROM arbiter_provincia.policy_snapshot));

INSERT INTO arbiter_provincia.cases
    (id, occurred_at, reported_at, police_report_at, response_deadline, description,
     was_fast_track, claimed_amount, declared_item, event_address, locality, province,
     current_status_id, analyst_id, insured_id, claim_cause_id, coverage_id, policy_id,
     policy_snapshot_id, scoring_configuration_id) VALUES
    (3, '2025-08-14 20:00:00+00', '2025-08-15 09:30:00+00', '2025-08-15 08:00:00+00', '2025-09-14',
     'Me robaron el celular en la estación, forcejeo incluido, hice la denuncia enseguida.',
     FALSE, 300000.00, 'iPhone 13 Mini', 'Estación San Martín', 'San Martín', 'Buenos Aires',
     5, 1, 2, 2, 1, 4, 3, 1),
    (4, '2026-08-01 22:30:00+00', '2026-08-02 09:00:00+00', '2026-08-02 08:30:00+00', '2026-09-01',
     'Segundo robo del año, esta vez cerca de casa, dos en moto.',
     FALSE, 640000.00, 'iPhone 13 Mini', 'Villa Ballester', 'San Martín', 'Buenos Aires',
     2, 2, 2, 2, 1, 4, 4, 1),
    (5, '2026-08-05 21:00:00+00', '2026-08-06 08:30:00+00', NULL, '2026-09-05',
     'Me arrebataron el celular en la parada, la póliza la tengo con una cuota atrasada.',
     FALSE, 430000.00, 'Samsung Galaxy A34', 'San Andrés', 'San Martín', 'Buenos Aires',
     2, 1, 2, 2, 1, 5, 5, 1),
    (6, '2026-08-07 10:00:00+00', '2026-08-07 11:00:00+00', NULL, '2026-09-06',
     'Hurto del celular en el trabajo, me falta subir la denuncia policial.',
     FALSE, 200000.00, 'Samsung Galaxy A34', 'Chacarita', 'CABA', 'Buenos Aires',
     3, NULL, 2, 3, 1, 5, NULL, NULL),
    (7, '2026-08-11 20:00:00+00', '2026-08-12 08:00:00+00', NULL, '2026-09-11',
     'Me robaron el celular volviendo del trabajo, recién cargo la denuncia.',
     FALSE, 780000.00, 'iPhone 13 Mini', 'Saavedra', 'CABA', 'Buenos Aires',
     1, NULL, 2, 2, 1, 4, NULL, NULL),
    (8, '2025-09-10 18:00:00+00', '2025-09-11 09:00:00+00', NULL, '2025-10-11',
     'Se me cayó la notebook y se rompió la pantalla, enciende pero no muestra imagen.',
     FALSE, 55000.00, 'Dell XPS 13', 'Av. Rivadavia 4820', 'CABA', 'Buenos Aires',
     5, 1, 1, 6, 3, 6, 6, 1),
    (9, '2026-08-09 23:00:00+00', '2026-08-10 08:30:00+00', '2026-08-10 08:00:00+00', '2026-09-09',
     'Robo del celular con intimidación cerca de un cajero, monto alto porque es el tope de gama.',
     FALSE, 1500000.00, 'iPhone 15 Pro Max', 'Recoleta', 'CABA', 'Buenos Aires',
     2, 2, 1, 2, 1, 7, 7, 1),
    (10, '2026-07-15 02:00:00+00', '2026-07-16 12:00:00+00', NULL, '2026-08-15',
     'Me robaron el celular de madrugada, no recuerdo bien dónde fue.',
     FALSE, 880000.00, 'Samsung Galaxy S23', 'Palermo', 'CABA', 'Buenos Aires',
     6, 1, 1, 2, 1, 2, 8, 1),
    (11, '2026-08-06 21:30:00+00', '2026-08-07 09:00:00+00', '2026-08-07 08:15:00+00', '2026-09-06',
     'Robo del celular en la vía pública, tengo la denuncia y las fotos.',
     FALSE, 690000.00, 'Samsung Galaxy A56', 'Once', 'CABA', 'Buenos Aires',
     2, 2, 1, 2, 1, 3, 9, 1),
    (12, '2026-08-08 13:00:00+00', '2026-08-08 14:00:00+00', NULL, '2026-09-07',
     'Se mojó la notebook y no enciende, me falta el comprobante de compra.',
     FALSE, 90000.00, 'Dell XPS 13', 'Belgrano', 'CABA', 'Buenos Aires',
     3, NULL, 1, 6, 3, 6, NULL, NULL),
    (13, '2026-08-04 11:00:00+00', '2026-08-04 11:20:00+00', NULL, '2026-09-03',
     'Golpe accidental en la notebook, se rajó la pantalla pero funciona.',
     TRUE, 70000.00, 'Dell XPS 13', 'Caballito', 'CABA', 'Buenos Aires',
     2, NULL, 1, 6, 3, 6, 10, 1),
    (14, '2026-07-30 19:00:00+00', '2026-07-31 09:00:00+00', NULL, '2026-08-30',
     'Robo en la vía pública, la clasificación falló por un error del sistema.',
     FALSE, 260000.00, 'iPhone 13 Mini', 'Villa Urquiza', 'CABA', 'Buenos Aires',
     4, NULL, 2, 2, 1, 4, NULL, NULL);
SELECT setval(pg_get_serial_sequence('arbiter_provincia.cases','id'), (SELECT MAX(id) FROM arbiter_provincia.cases));

INSERT INTO arbiter_provincia.llm_analysis (id, recommendation, model, prompt_version, confidence,
                                   latency_ms, analyzed_at, case_id) VALUES
    (3, 'LLM_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.900, 4800, '2025-08-15 09:37:00+00', 3),
    (4, 'LLM_SOLICITA_REVISION_MANUAL', 'qwen3-vl', 'classification-v1', 0.620, 4800, '2026-08-02 09:07:00+00', 4),
    (5, 'LLM_NO_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.940, 4800, '2026-08-06 08:37:00+00', 5),
    (6, 'FALTA_DOCUMENTACION', 'qwen3-vl', 'classification-v1', 1.000, 3200, '2026-08-07 11:07:00+00', 6),
    (7, 'LLM_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.900, 4800, '2025-09-11 09:07:00+00', 8),
    (8, 'LLM_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.900, 4800, '2026-08-10 08:37:00+00', 9),
    (9, 'LLM_NO_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.940, 4800, '2026-07-16 12:07:00+00', 10),
    (10, 'LLM_RECOMIENDA_APROBAR', 'qwen3-vl', 'classification-v1', 0.900, 4800, '2026-08-07 09:07:00+00', 11),
    (11, 'FALTA_DOCUMENTACION', 'qwen3-vl', 'classification-v1', 1.000, 3200, '2026-08-08 14:07:00+00', 12);
SELECT setval(pg_get_serial_sequence('arbiter_provincia.llm_analysis','id'), (SELECT MAX(id) FROM arbiter_provincia.llm_analysis));

INSERT INTO arbiter_provincia.llm_reason (reason, analysis_id) VALUES
    ('Denuncia policial coherente', 3),
    ('Sin siniestros previos', 3),
    ('Monto razonable', 3),
    ('Segundo siniestro del asegurado en el año', 4),
    ('Relato consistente pero conviene revisión', 4),
    ('Monto elevado respecto de la suma asegurada', 4),
    ('La póliza registra cuotas impagas', 5),
    ('El asegurado tiene un siniestro previo', 5),
    ('El monto es alto respecto de la suma asegurada', 5),
    ('Falta documento requerido: police_report', 6),
    ('Falta documento requerido: purchase_proof', 6),
    ('Daño consistente con las fotos adjuntas', 7),
    ('Monto acorde al daño', 7),
    ('Sin siniestros previos', 7),
    ('Denuncia policial consistente', 8),
    ('Sin reincidencia', 8),
    ('Monto alto pero dentro de la suma asegurada', 8),
    ('Mora en la póliza y reincidencia (2 previos)', 9),
    ('Relato impreciso sobre lugar y hora', 9),
    ('Perfil de riesgo crítico', 9),
    ('Documentación completa', 10),
    ('Sin siniestros previos', 10),
    ('Monto acorde', 10),
    ('Falta documento requerido: purchase_proof', 11);

INSERT INTO arbiter_provincia.risk_analysis (id, risk_score, risk_band, risk_breakdown, analyzed_at, case_id) VALUES
    (3, 0.169, 'LOW',
     '[{"factorId":"amount_ratio","rawScore":0.375,"weight":0.45,"weightedContribution":0.1688,"rationale":"Monto reclamado es 38% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2025-08-15 09:36:00+00', 3),
    (4, 0.477, 'MEDIUM',
     '[{"factorId":"amount_ratio","rawScore":0.8,"weight":0.45,"weightedContribution":0.36,"rationale":"Monto reclamado es 80% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.3333,"weight":0.35,"weightedContribution":0.1167,"rationale":"Siniestros previos del asegurado: 1"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-08-02 09:06:00+00', 4),
    (5, 0.747, 'HIGH',
     '[{"factorId":"amount_ratio","rawScore":0.9556,"weight":0.45,"weightedContribution":0.43,"rationale":"Monto reclamado es 96% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.3333,"weight":0.35,"weightedContribution":0.1167,"rationale":"Siniestros previos del asegurado: 1"},{"factorId":"policy_standing","rawScore":1.0,"weight":0.2,"weightedContribution":0.2,"rationale":"La póliza registra cuotas impagas"}]'::jsonb,
     '2026-08-06 08:36:00+00', 5),
    (6, 0.206, 'LOW',
     '[{"factorId":"amount_ratio","rawScore":0.4583,"weight":0.45,"weightedContribution":0.2062,"rationale":"Monto reclamado es 46% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2025-09-11 09:06:00+00', 8),
    (7, 0.422, 'MEDIUM',
     '[{"factorId":"amount_ratio","rawScore":0.9375,"weight":0.45,"weightedContribution":0.4219,"rationale":"Monto reclamado es 94% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-08-10 08:36:00+00', 9),
    (8, 0.873, 'CRITICAL',
     '[{"factorId":"amount_ratio","rawScore":0.9778,"weight":0.45,"weightedContribution":0.44,"rationale":"Monto reclamado es 98% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.6667,"weight":0.35,"weightedContribution":0.2333,"rationale":"Siniestros previos del asegurado: 2"},{"factorId":"policy_standing","rawScore":1.0,"weight":0.2,"weightedContribution":0.2,"rationale":"La póliza registra cuotas impagas"}]'::jsonb,
     '2026-07-16 12:06:00+00', 10),
    (9, 0.444, 'MEDIUM',
     '[{"factorId":"amount_ratio","rawScore":0.9857,"weight":0.45,"weightedContribution":0.4436,"rationale":"Monto reclamado es 99% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-08-07 09:06:00+00', 11),
    (10, 0.263, 'LOW',
     '[{"factorId":"amount_ratio","rawScore":0.5833,"weight":0.45,"weightedContribution":0.2625,"rationale":"Monto reclamado es 58% de la suma asegurada"},{"factorId":"claim_frequency","rawScore":0.0,"weight":0.35,"weightedContribution":0.0,"rationale":"Siniestros previos del asegurado: 0"},{"factorId":"policy_standing","rawScore":0.0,"weight":0.2,"weightedContribution":0.0,"rationale":"Póliza al día con sus pagos"}]'::jsonb,
     '2026-08-04 11:26:00+00', 13);
SELECT setval(pg_get_serial_sequence('arbiter_provincia.risk_analysis','id'), (SELECT MAX(id) FROM arbiter_provincia.risk_analysis));

INSERT INTO arbiter_provincia.rule_result (rule_type, result, evaluated_value, score_contribution,
                                  evaluated_at, rule_id, case_id) VALUES
    ('FAST_TRACK', 'CUMPLE', '0.583', 0.2625, '2026-08-04 11:25:00+00', 1, 13),
    ('FAST_TRACK', 'CUMPLE', 'AL_DIA', 0.0000, '2026-08-04 11:25:00+00', 2, 13);

INSERT INTO arbiter_provincia.case_classification (id, decision, analyst_justification, decided_at,
                                          classification_attempts, llm_analysis_id, analyst_id) VALUES
    (2, 'APROBAR', 'Caso consistente y documentado. Aprobado.',
     '2025-08-15 10:00:00+00', 1, 3, 1),
    (3, 'APROBAR', 'Daño accidental documentado. Coincido con el modelo, aprobado.',
     '2025-09-11 09:30:00+00', 1, 7, 1),
    (4, 'RECHAZAR', 'Mora, reincidencia y relato impreciso. Se rechaza.',
     '2026-07-16 12:30:00+00', 1, 9, 1);
SELECT setval(pg_get_serial_sequence('arbiter_provincia.case_classification','id'), (SELECT MAX(id) FROM arbiter_provincia.case_classification));

INSERT INTO arbiter_provincia.case_status_history (reason, observation, actor, changed_at, changed_by,
                                          initial_status_id, final_status_id, case_id) VALUES
    ('Denuncia registrada', NULL, 'INSURED', '2025-08-15 09:30:00+00', 5, NULL, 1, 3),
    ('Clasificación disponible', NULL, 'SYSTEM', '2025-08-15 09:37:00+00', NULL, 1, 2, 3),
    ('El analista aprobó el siniestro', NULL, 'ANALYST', '2025-08-15 10:00:00+00', 4, 2, 5, 3),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-02 09:00:00+00', 5, NULL, 1, 4),
    ('Clasificación disponible', NULL, 'SYSTEM', '2026-08-02 09:07:00+00', NULL, 1, 2, 4),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-06 08:30:00+00', 5, NULL, 1, 5),
    ('Clasificación disponible', NULL, 'SYSTEM', '2026-08-06 08:37:00+00', NULL, 1, 2, 5),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-07 11:00:00+00', 5, NULL, 1, 6),
    ('Faltan documentos obligatorios de la agenda', NULL, 'SYSTEM', '2026-08-07 11:07:00+00', NULL, 1, 3, 6),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-12 08:00:00+00', 5, NULL, 1, 7),
    ('Denuncia registrada', NULL, 'INSURED', '2025-09-11 09:00:00+00', 1, NULL, 1, 8),
    ('Clasificación disponible', NULL, 'SYSTEM', '2025-09-11 09:07:00+00', NULL, 1, 2, 8),
    ('El analista aprobó el siniestro', NULL, 'ANALYST', '2025-09-11 09:30:00+00', 4, 2, 5, 8),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-10 08:30:00+00', 1, NULL, 1, 9),
    ('Clasificación disponible', NULL, 'SYSTEM', '2026-08-10 08:37:00+00', NULL, 1, 2, 9),
    ('Denuncia registrada', NULL, 'INSURED', '2026-07-16 12:00:00+00', 1, NULL, 1, 10),
    ('Clasificación disponible', NULL, 'SYSTEM', '2026-07-16 12:07:00+00', NULL, 1, 2, 10),
    ('El analista rechazó el siniestro', NULL, 'ANALYST', '2026-07-16 12:30:00+00', 4, 2, 6, 10),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-07 09:00:00+00', 1, NULL, 1, 11),
    ('Clasificación disponible', NULL, 'SYSTEM', '2026-08-07 09:07:00+00', NULL, 1, 2, 11),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-08 14:00:00+00', 1, NULL, 1, 12),
    ('Faltan documentos obligatorios de la agenda', NULL, 'SYSTEM', '2026-08-08 14:07:00+00', NULL, 1, 3, 12),
    ('Denuncia registrada', NULL, 'INSURED', '2026-08-04 11:20:00+00', 1, NULL, 1, 13),
    ('Fast Track determinístico: cumple todas las reglas', NULL, 'SYSTEM', '2026-08-04 11:26:00+00', NULL, 1, 2, 13),
    ('Denuncia registrada', NULL, 'INSURED', '2026-07-31 09:00:00+00', 5, NULL, 1, 14),
    ('La clasificación falló tras agotar reintentos', NULL, 'SYSTEM', '2026-07-31 09:06:00+00', NULL, 1, 4, 14);

INSERT INTO arbiter_provincia.notification (type, channel, content, sent, read, sent_at, read_at,
                                   recipient_id, case_id) VALUES
    ('CAMBIO_ESTADO', 'EMAIL', 'Tu siniestro fue aprobado. En los próximos días vas a recibir el detalle de la liquidación.', TRUE, FALSE, '2025-08-15 10:00:00+00', NULL, 2, 3),
    ('CAMBIO_ESTADO', 'EMAIL', 'Tu siniestro fue aprobado. En los próximos días vas a recibir el detalle de la liquidación.', TRUE, FALSE, '2025-09-11 09:30:00+00', NULL, 1, 8),
    ('CAMBIO_ESTADO', 'EMAIL', 'Tu siniestro fue rechazado. Podés ver el detalle y los motivos en el portal.', TRUE, FALSE, '2026-07-16 12:30:00+00', NULL, 1, 10);

-- Enlazar cada caso cerrado con su clasificación (human-in-the-loop).
UPDATE arbiter_bbva.cases SET classification_id = 1 WHERE id = 6;
UPDATE arbiter_bbva.cases SET classification_id = 2 WHERE id = 7;
UPDATE arbiter_bbva.cases SET classification_id = 3 WHERE id = 8;
UPDATE arbiter_bbva.cases SET classification_id = 4 WHERE id = 16;
UPDATE arbiter_bbva.cases SET classification_id = 5 WHERE id = 17;
UPDATE arbiter_provincia.cases SET classification_id = 2 WHERE id = 3;
UPDATE arbiter_provincia.cases SET classification_id = 3 WHERE id = 8;
UPDATE arbiter_provincia.cases SET classification_id = 4 WHERE id = 10;

-- Recontar expedientes por asegurado.
UPDATE arbiter_bbva.insured i SET case_count = (SELECT COUNT(*) FROM arbiter_bbva.cases c WHERE c.insured_id = i.id);
UPDATE arbiter_provincia.insured i SET case_count = (SELECT COUNT(*) FROM arbiter_provincia.cases c WHERE c.insured_id = i.id);

-- Copiar el score al read-model de cases (la bandeja lee cases.risk_band, no risk_analysis).
UPDATE arbiter_bbva.cases c
SET risk_score = ra.risk_score, risk_band = ra.risk_band
FROM (SELECT DISTINCT ON (case_id) case_id, risk_score, risk_band
      FROM arbiter_bbva.risk_analysis ORDER BY case_id, analyzed_at DESC) ra
WHERE ra.case_id = c.id;
UPDATE arbiter_provincia.cases c
SET risk_score = ra.risk_score, risk_band = ra.risk_band
FROM (SELECT DISTINCT ON (case_id) case_id, risk_score, risk_band
      FROM arbiter_provincia.risk_analysis ORDER BY case_id, analyzed_at DESC) ra
WHERE ra.case_id = c.id;


COMMIT;
