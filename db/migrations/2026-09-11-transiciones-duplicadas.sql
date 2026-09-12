-- =============================================================================
-- 2026-09-11 · Borrar las transiciones duplicadas que dejaron los barridos
--
-- Migración puntual de limpieza. NO toca estructura: borra filas espurias de
-- case_status_history, nada más. Aplicar sobre Railway (en una base recién
-- inicializada no hay nada que limpiar).
--
-- ¿De dónde salieron?
--   ClassificationServiceClient.refreshClassification y LapseSweepScheduler
--   validaban el estado del expediente contra la COPIA que el barrido cargó al
--   principio de la vuelta, no contra la base. Como la base de Railway es
--   compartida por todo el equipo, cada stack local levantado suma otro
--   scheduler barriendo los mismos expedientes: dos barridos leían la misma
--   copia en PENDING_CLASSIFICATION (o AWAITING_DOCUMENTATION), los dos
--   resolvían y los dos escribían la transición. De ahí dos filas idénticas
--   separadas por segundos — el síntoma visible es el timeline del expediente
--   mostrando dos veces el mismo cambio de estado.
--
--   El bug de origen ya está arreglado: las dos rutas pasaron a
--   CaseStatusService.transitionIfStillIn, que toma el turno con un
--   compare-and-set (CaseRepository.claimStatusTransition) y no escribe nada si
--   otro barrido llegó primero. Esto solo saca lo que quedó de antes.
--
-- Criterio de duplicado (a propósito, estrecho):
--   misma case_id, mismo initial_status_id, mismo final_status_id, mismo reason,
--   mismo actor, mismo changed_by, y a MENOS DE 60 SEGUNDOS de una fila anterior
--   igual. Se conserva la primera de cada ráfaga (la que efectivamente movió el
--   expediente) y se borran las que la siguen.
--
--   La ventana importa: un expediente SÍ puede pasar dos veces por la misma
--   transición con el mismo motivo de forma legítima — el analista reintenta la
--   clasificación, o el asegurado sube lo que faltaba y el modelo vuelve a dar el
--   mismo resultado. Esas están separadas por minutos u horas, nunca por
--   segundos: una corrida del modelo sola ya tarda más que la ventana.
--
--   Y solo actor = 'SYSTEM': los duplicados conocidos son todos de los barridos.
--   Ninguna fila escrita por una persona (INSURED / ANALYST) se toca, aunque el
--   SELECT de inspección de abajo sí las mira, para no dejar de verlas si las hay.
--
-- ANTES DE APLICAR: correr la inspección. Este script no fue verificado contra la
-- base viva — a diferencia de 2026-08-21-limpiar-policy-leak-bbva.sql, acá el
-- criterio se escribió desde el código, no desde los datos.
--
-- Idempotente: una segunda corrida no encuentra nada que borrar.
-- =============================================================================

-- ─── INSPECCIÓN — correr esto PRIMERO, y mirar lo que sale ──────────────────
-- Lista todo lo que el DELETE de abajo se llevaría, más las filas de personas que
-- cumplirían el criterio pero quedan afuera del borrado (columna `se_borra`).
--
-- SELECT h.case_id,
--        h.id,
--        h.changed_at,
--        h.actor,
--        i.name AS desde,
--        f.name AS hasta,
--        h.reason,
--        (h.actor = 'SYSTEM') AS se_borra
--   FROM arbiter_bbva.case_status_history h
--   LEFT JOIN arbiter_common.case_status i ON i.id = h.initial_status_id
--   JOIN arbiter_common.case_status f ON f.id = h.final_status_id
--  WHERE EXISTS (SELECT 1
--                  FROM arbiter_bbva.case_status_history keep
--                 WHERE keep.case_id = h.case_id
--                   AND keep.initial_status_id IS NOT DISTINCT FROM h.initial_status_id
--                   AND keep.final_status_id = h.final_status_id
--                   AND keep.reason = h.reason
--                   AND keep.actor = h.actor
--                   AND keep.changed_by IS NOT DISTINCT FROM h.changed_by
--                   AND (keep.changed_at, keep.id) < (h.changed_at, h.id)
--                   AND h.changed_at - keep.changed_at <= INTERVAL '60 seconds')
--  ORDER BY h.case_id, h.changed_at;
--
-- Ídem para arbiter_provincia (cambiar los dos arbiter_bbva por arbiter_provincia).

BEGIN;

DELETE FROM arbiter_bbva.case_status_history h
 WHERE h.actor = 'SYSTEM'
   AND EXISTS (SELECT 1
                 FROM arbiter_bbva.case_status_history keep
                WHERE keep.case_id = h.case_id
                  AND keep.initial_status_id IS NOT DISTINCT FROM h.initial_status_id
                  AND keep.final_status_id = h.final_status_id
                  AND keep.reason = h.reason
                  AND keep.actor = h.actor
                  AND keep.changed_by IS NOT DISTINCT FROM h.changed_by
                  -- La que se conserva es la anterior; el par (changed_at, id) desempata
                  -- las que caigan en el mismo timestamp.
                  AND (keep.changed_at, keep.id) < (h.changed_at, h.id)
                  AND h.changed_at - keep.changed_at <= INTERVAL '60 seconds');

DELETE FROM arbiter_provincia.case_status_history h
 WHERE h.actor = 'SYSTEM'
   AND EXISTS (SELECT 1
                 FROM arbiter_provincia.case_status_history keep
                WHERE keep.case_id = h.case_id
                  AND keep.initial_status_id IS NOT DISTINCT FROM h.initial_status_id
                  AND keep.final_status_id = h.final_status_id
                  AND keep.reason = h.reason
                  AND keep.actor = h.actor
                  AND keep.changed_by IS NOT DISTINCT FROM h.changed_by
                  AND (keep.changed_at, keep.id) < (h.changed_at, h.id)
                  AND h.changed_at - keep.changed_at <= INTERVAL '60 seconds');

COMMIT;

-- Verificación: las dos consultas tienen que devolver 0 filas después de aplicar.
--
-- SELECT COUNT(*) FROM arbiter_bbva.case_status_history h
--  WHERE h.actor = 'SYSTEM'
--    AND EXISTS (SELECT 1 FROM arbiter_bbva.case_status_history keep
--                 WHERE keep.case_id = h.case_id
--                   AND keep.initial_status_id IS NOT DISTINCT FROM h.initial_status_id
--                   AND keep.final_status_id = h.final_status_id
--                   AND keep.reason = h.reason
--                   AND keep.actor = h.actor
--                   AND keep.changed_by IS NOT DISTINCT FROM h.changed_by
--                   AND (keep.changed_at, keep.id) < (h.changed_at, h.id)
--                   AND h.changed_at - keep.changed_at <= INTERVAL '60 seconds');
--
-- Ídem arbiter_provincia.
--
-- Y el expediente del reporte original, para mirarlo con los ojos:
-- SELECT h.id, h.changed_at, h.actor, i.name AS desde, f.name AS hasta, h.reason
--   FROM arbiter_bbva.case_status_history h
--   LEFT JOIN arbiter_common.case_status i ON i.id = h.initial_status_id
--   JOIN arbiter_common.case_status f ON f.id = h.final_status_id
--  WHERE h.case_id = :case_id
--  ORDER BY h.changed_at;
