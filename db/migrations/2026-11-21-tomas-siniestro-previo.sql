-- 2026-11-21 · Tomás Ibarra (final presentation, case 2) gets one prior claim, so his history is not
-- blank: the claim frequency risk factor shows "Siniestros previos del asegurado: 1".
-- It goes on an earlier BBVA policy that already expired, not on POL-TEC-2026-640: that one allows
-- two events a year, and a claim paid against its damage coverage would eat into the 600.000.
-- cobertura_id stays NULL for the same reason: the coverage exhaustion rule skips the row.
-- Insurer database only. scripts/reset-asegurados-de-prueba.sql doesn't touch it.
-- Idempotent: looked up by DNI and policy number.

BEGIN;

SELECT setval(pg_get_serial_sequence('aseguradora_bbva.poliza', 'id'),
              (SELECT max(id) FROM aseguradora_bbva.poliza));
SELECT setval(pg_get_serial_sequence('aseguradora_bbva.siniestro_historico', 'id'),
              (SELECT max(id) FROM aseguradora_bbva.siniestro_historico));

INSERT INTO aseguradora_bbva.poliza (numero, nro_certificado, titular_id, rama, producto, vigencia_desde,
                                     vigencia_hasta, estado_contrato, estado_pago, cuotas_pagas,
                                     cuotas_impagas, saldo_deuda, forma_pago, max_eventos_anuales,
                                     segundo_evento_pct, cubre_grupo_familiar, importe_cuota)
SELECT 'POL-TEC-2025-118', '700118', a.id, U&'Tecnolog\00EDa Port\00E1til', U&'Tecnolog\00EDa Protegida',
       TIMESTAMP '2025-03-01 00:00:00', TIMESTAMP '2026-03-01 23:59:59', 'VENCIDA', 'AL_DIA', 12, 0, 0.00,
       'TARJETA DE CREDITO', 2, 50.00, FALSE, 9900.00
  FROM aseguradora_bbva.asegurado a
 WHERE a.documento = '37.905.162'
   AND NOT EXISTS (SELECT 1 FROM aseguradora_bbva.poliza x WHERE x.numero = 'POL-TEC-2025-118');

INSERT INTO aseguradora_bbva.siniestro_historico (poliza_id, cobertura_id, asegurado_id, fecha_ocurrencia,
                                                  causa, estado_resolucion, monto_indemnizado)
SELECT p.id, NULL, p.titular_id, DATE '2025-08-14', 'Hurto', 'LIQUIDADO', 310000.00
  FROM aseguradora_bbva.poliza p
 WHERE p.numero = 'POL-TEC-2025-118'
   AND NOT EXISTS (SELECT 1 FROM aseguradora_bbva.siniestro_historico h WHERE h.poliza_id = p.id);

COMMIT;
