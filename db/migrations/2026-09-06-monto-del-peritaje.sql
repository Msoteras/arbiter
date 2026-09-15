-- =============================================================================
-- 2026-09-06 · El peritaje determina un monto (bloque 5)
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- El procedimiento de la compañía dice que el perito verifica "la causa del
-- siniestro como el monto indemnizable" (NSIN001 §2.6). Arbiter le venía
-- registrando solo la causa: el peritaje traía veredicto y nota, y el monto
-- quedaba en el PDF del informe, que nadie lee.
--
-- Lo carga el analista junto con el veredicto, en el mismo formulario donde ya
-- transcribe la conclusión: el perito está fuera del sistema y contesta por
-- mail, así que el número llega en un archivo y alguien tiene que pasarlo.
--
-- Nullable a propósito: no todo peritaje termina en un monto. Un fraude
-- confirmado o un hecho no amparado no tienen nada que indemnizar, y un cero ahí
-- se leería como "el perito dijo que no se paga nada" — que es una conclusión
-- distinta a no haber opinado sobre el monto.
--
-- IMPORTANTE: los servicios corren con ddl-auto=validate. Aplicar ANTES de
-- desplegar el código, o cases-service no levanta.
-- =============================================================================

BEGIN;

ALTER TABLE arbiter_bbva.expert_assessment
    ADD COLUMN IF NOT EXISTS indemnifiable_amount NUMERIC(15,2);
ALTER TABLE arbiter_provincia.expert_assessment
    ADD COLUMN IF NOT EXISTS indemnifiable_amount NUMERIC(15,2);

COMMIT;

-- Verificación:
-- SELECT case_id, verdict, indemnifiable_amount FROM arbiter_bbva.expert_assessment;
