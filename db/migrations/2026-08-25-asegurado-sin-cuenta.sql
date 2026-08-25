-- =============================================================================
-- 2026-08-25 · Camila Ferreyra — asegurada de BBVA SIN cuenta en la plataforma
--
-- Migración puntual y NO destructiva, para aplicar sobre una base con datos
-- (Railway) sin pasar por el trío reset → init → seed.
--
-- Para qué: probar el alta masiva ("Dar de alta asegurados") de punta a punta.
-- Hasta ahora no se podía: los 3 asegurados de BBVA con póliza vigente YA tienen
-- cuenta, así que una corrida no crea nada ni manda un solo mail — buen test de
-- idempotencia, inútil para ver el flujo completo.
--
-- Por eso esto toca SOLO la BD Aseguradora. Deliberadamente NO crea:
--   · arbiter_common.users / user_role / user_insurer
--   · arbiter_bbva.insured
-- Eso es exactamente lo que el botón tiene que crear. Insertarlo acá sería
-- escribir a mano lo que se quiere probar, y la corrida volvería a no hacer nada.
--
-- El mail es un alias con '+' de una casilla que el equipo ya usa: Gmail lo
-- entrega en el mismo inbox, pero como string es distinto de cualquier fila de
-- `users`, así que la invitación llega a algún lado y el de-dup por email no la
-- confunde con la cuenta de Roman.
--
-- Coberturas con los mismos nombres que el resto ('Robo de celular', 'Hurto'):
-- PolicySynchronizer las matchea por nombre contra arbiter_bbva.coverage, y una
-- que no exista ahí haría fallar el alta de la denuncia más adelante.
--
-- Idempotente: se puede correr más de una vez.
-- =============================================================================

BEGIN;

INSERT INTO aseguradora_bbva.asegurado (id, documento, cuil, nombre, apellido, email, telefono) VALUES
    (4, '38.412.905', '27-38412905-4', 'Camila', 'Ferreyra',
     'asandoval01228+alta@gmail.com', '11-5555-0042')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.asegurado', 'id'),
              (SELECT MAX(id) FROM aseguradora_bbva.asegurado));

-- Vigente hoy: es lo único que el alta masiva mira (vigencia_hasta >= NOW()).
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

-- Verificación: tiene que aparecer con ya_tiene_cuenta = f. Si sale t, el alta
-- masiva la va a saltear por reusada y no se prueba nada.
SELECT DISTINCT a.documento, a.nombre, a.apellido, a.email,
       (u.id IS NOT NULL) AS ya_tiene_cuenta
  FROM aseguradora_bbva.asegurado a
  JOIN aseguradora_bbva.poliza p ON p.titular_id = a.id
  LEFT JOIN arbiter_common.users u ON lower(u.email) = lower(a.email)
 WHERE p.vigencia_hasta >= NOW()
 ORDER BY a.apellido;
