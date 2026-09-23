-- =============================================================================
-- 2026-09-22 · Valentín Aguirre — asegurado de BBVA exclusivo para las mutaciones
--
-- Migración puntual y NO destructiva, para aplicar sobre una base con datos (Railway).
--
-- Para qué: las mutaciones de docs/postman/test-docs/mutaciones-celulares.js necesitan un
-- asegurado sin historial. Cada caso cargado en Arbiter cuenta como siniestro previo de
-- quien lo cargó: Martina ya acumula 15 en BBVA y Roman 1, y con uno solo ya falla el
-- máximo de siniestros previos del Fast Track (0) antes de que el documento mutado tenga
-- la oportunidad de mover algo. Este asegurado se limpia antes de
-- cada mutación con scripts/reset-asegurados-de-prueba.sql.
--
-- Igual que 2026-08-25-asegurado-sin-cuenta.sql, toca SOLO la BD Aseguradora. La cuenta,
-- arbiter_bbva.insured y la invitación salen del alta masiva del referente, que es el
-- camino real: sin login de Auth0 no hay forma de cargar un caso a su nombre (solo el
-- asegurado denuncia, y solo a nombre propio). Después del alta masiva, la póliza la
-- trae PolicySynchronizer la primera vez que se la pide.
--
-- Los datos coinciden con perfiles.js (perfil `mutaciones`): si se cambia uno, cambiar el
-- otro. Mismos montos que la póliza de Martina (POL-CEL-2026-042), así el caso base se
-- comporta igual que el robo de siempre. Coberturas con los mismos nombres que el resto:
-- PolicySynchronizer las matchea por nombre contra arbiter_bbva.coverage.
--
-- Idempotente: se puede correr más de una vez.
-- =============================================================================

BEGIN;

-- The email is a real mailbox on purpose: the bulk provisioning sends the invitation there, and
-- an account nobody can log into can't file a case.
INSERT INTO aseguradora_bbva.asegurado (id, documento, cuil, nombre, apellido, email, telefono) VALUES
    (5, '38.614.270', '20-38614270-0', 'Valentín', 'Aguirre', 'aylusandu@gmail.com', '11-5555-0077')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.asegurado', 'id'),
              (SELECT MAX(id) FROM aseguradora_bbva.asegurado));

-- Vigente hoy: es lo único que el alta masiva mira (vigencia_hasta >= NOW()).
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

-- Verificación: tiene que aparecer con ya_tiene_cuenta = f hasta que corra el alta masiva.
SELECT DISTINCT a.documento, a.nombre, a.apellido, a.email,
       (u.id IS NOT NULL) AS ya_tiene_cuenta
  FROM aseguradora_bbva.asegurado a
  JOIN aseguradora_bbva.poliza p ON p.titular_id = a.id
  LEFT JOIN arbiter_common.users u ON lower(u.email) = lower(a.email)
 WHERE a.documento = '38.614.270';
