-- =============================================================================
-- 2026-09-06 · Fórmula de reparación (bloque 3)
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Hasta acá toda liquidación se calculaba como pérdida total. Pero el bien no
-- siempre desaparece: en el daño por tentativa de robo queda dañado, se repara,
-- y la póliza NO se extingue. Son dos fórmulas distintas:
--
--   TOTAL_LOSS  suma asegurada − franquicia − cuotas a vencer − deuda vencida
--   REPAIR      presupuesto (tope: suma asegurada) − franquicia − deuda vencida
--
-- La diferencia que importa son las cuotas a vencer. Se descuentan porque la
-- pérdida total extingue el contrato y el premio que resta del año se cobra de
-- la indemnización; después de una reparación el contrato sigue vivo y el
-- asegurado lo sigue pagando mes a mes. Descontárselas ahí sería cobrarle el
-- resto del año a alguien que todavía tiene la cobertura que está pagando.
--
-- Y el presupuesto pasa a ser la BASE, no evidencia opcional: sin presupuesto no
-- hay monto que pagar, y la propuesta sale en cero en vez de caer a la suma
-- asegurada.
--
-- IMPORTANTE: los servicios corren con ddl-auto=validate. Aplicar ANTES de
-- desplegar el código, o cases-service no levanta.
-- =============================================================================

BEGIN;

-- ─── 1 · coverage: qué fórmula liquida cada cobertura ───────────────────────
-- Va en la cobertura y no en el hecho generador porque el catálogo de la
-- aseguradora ya las separa así ("Robo de celular" y "Hurto" son pérdidas,
-- "Daño accidental" es daño), y PolicyCoverageResolver ya resuelve cuál
-- cobertura responde por cada causa.
--
-- El default TOTAL_LOSS mantiene el comportamiento actual: es como venían
-- liquidando todas hasta hoy.
ALTER TABLE arbiter_bbva.coverage
    ADD COLUMN IF NOT EXISTS settlement_formula VARCHAR(20) NOT NULL DEFAULT 'TOTAL_LOSS';
ALTER TABLE arbiter_provincia.coverage
    ADD COLUMN IF NOT EXISTS settlement_formula VARCHAR(20) NOT NULL DEFAULT 'TOTAL_LOSS';

ALTER TABLE arbiter_bbva.coverage
    DROP CONSTRAINT IF EXISTS coverage_settlement_formula_check;
ALTER TABLE arbiter_bbva.coverage
    ADD CONSTRAINT coverage_settlement_formula_check
        CHECK (settlement_formula IN ('TOTAL_LOSS', 'REPAIR'));

ALTER TABLE arbiter_provincia.coverage
    DROP CONSTRAINT IF EXISTS coverage_settlement_formula_check;
ALTER TABLE arbiter_provincia.coverage
    ADD CONSTRAINT coverage_settlement_formula_check
        CHECK (settlement_formula IN ('TOTAL_LOSS', 'REPAIR'));

-- Las coberturas de daño reparan. Por nombre y no por id: es el nombre el que
-- dice qué le pasó al bien, y es lo que el referente ve en su pantalla. Una
-- cobertura que la aseguradora haya agregado después queda en TOTAL_LOSS y el
-- referente la cambia si corresponde.
UPDATE arbiter_bbva.coverage      SET settlement_formula = 'REPAIR' WHERE name = 'Daño accidental';
UPDATE arbiter_provincia.coverage SET settlement_formula = 'REPAIR' WHERE name = 'Daño accidental';

-- ─── 2 · case_settlement: la fórmula que se aplicó ──────────────────────────
ALTER TABLE arbiter_bbva.case_settlement
    DROP CONSTRAINT IF EXISTS case_settlement_formula_check;
ALTER TABLE arbiter_bbva.case_settlement
    ADD CONSTRAINT case_settlement_formula_check
        CHECK (formula IN ('TOTAL_LOSS', 'REPAIR'));

ALTER TABLE arbiter_provincia.case_settlement
    DROP CONSTRAINT IF EXISTS case_settlement_formula_check;
ALTER TABLE arbiter_provincia.case_settlement
    ADD CONSTRAINT case_settlement_formula_check
        CHECK (formula IN ('TOTAL_LOSS', 'REPAIR'));

-- Las liquidaciones ya firmadas sobre una cobertura de daño se reetiquetan: se
-- calcularon con la única fórmula que existía, pero el número que produjeron es
-- el de una reparación (el techo fue el presupuesto y no se descontaron cuotas),
-- así que la etiqueta estaba mal, no la cuenta.
UPDATE arbiter_bbva.case_settlement s
   SET formula = 'REPAIR'
  FROM arbiter_bbva.coverage c
 WHERE c.id = s.coverage_id AND c.settlement_formula = 'REPAIR' AND s.formula = 'TOTAL_LOSS';

UPDATE arbiter_provincia.case_settlement s
   SET formula = 'REPAIR'
  FROM arbiter_provincia.coverage c
 WHERE c.id = s.coverage_id AND c.settlement_formula = 'REPAIR' AND s.formula = 'TOTAL_LOSS';

COMMIT;

-- Verificación:
-- SELECT name, settlement_formula, settlement_basis FROM arbiter_provincia.coverage ORDER BY id;
-- SELECT case_id, formula, settled_amount FROM arbiter_provincia.case_settlement;
