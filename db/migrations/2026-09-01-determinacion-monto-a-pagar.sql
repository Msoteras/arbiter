-- =============================================================================
-- 2026-09-01 · Determinación del monto a pagar (bloque 1: pérdida total)
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- El analista determina cuánto se paga, no solo si se paga. Es un paso del
-- procedimiento de la compañía (NSIN001 §5.2.1.2, "Liquidación del Siniestro")
-- que el expediente no tenía: llegaba a APROBADO sin decir un monto.
--
-- La fórmula sale de los manuales de producto, no la inventamos:
--
--   Celulares:  suma asegurada − franquicia − cuotas pendientes de pago
--   Tec. Port.: además, el menor entre suma asegurada y valor de reposición
--               (art. 7, Bases de Indemnización), y el segundo evento del año
--               se paga al 50% ("dos eventos por año, primer evento hasta un
--               100% de la suma asegurada, segundo hasta un 50%").
--
--   Y de la cláusula 102 art. 5, en las dos: "aprobada la liquidación de un
--   siniestro el Asegurador podrá descontar de la indemnización cualquier saldo
--   o deuda vencida de este contrato".
--
-- Tres cambios:
--   1. coverage      — los parámetros que el referente configura por cobertura.
--   2. policy_snapshot — lo que la cuenta necesita congelado para ser
--                        reproducible meses después (D27 / Disposición 2/2023).
--   3. case_settlement — la liquidación en sí: entradas, deducciones y resultado.
--
-- Más `importe_cuota` en las BD Aseguradora, que es el único dato que faltaba
-- de origen: saldo_deuda cubre lo vencido, no lo que queda por vencer.
--
-- IMPORTANTE: los servicios corren con ddl-auto=validate. Aplicar ANTES de
-- desplegar el código, o cases-service no levanta.
-- =============================================================================

BEGIN;

-- ─── 1 · coverage: parámetros de liquidación ─────────────────────────────────
-- Los defaults reproducen el comportamiento del manual de Celulares, que es el
-- producto mayoritario: techo = suma asegurada, sin reducción por número de
-- evento. Las dos deducciones arrancan APAGADAS a propósito: prender una cambia
-- cuánto cobra el asegurado, y esa decisión es del referente, no de la migración.
ALTER TABLE arbiter_bbva.coverage
    ADD COLUMN IF NOT EXISTS settlement_basis            VARCHAR(30)  NOT NULL DEFAULT 'SUM_INSURED',
    ADD COLUMN IF NOT EXISTS second_event_percentage     NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS deduct_pending_installments BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS deduct_overdue_balance      BOOLEAN      NOT NULL DEFAULT FALSE;

ALTER TABLE arbiter_provincia.coverage
    ADD COLUMN IF NOT EXISTS settlement_basis            VARCHAR(30)  NOT NULL DEFAULT 'SUM_INSURED',
    ADD COLUMN IF NOT EXISTS second_event_percentage     NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS deduct_pending_installments BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS deduct_overdue_balance      BOOLEAN      NOT NULL DEFAULT FALSE;

ALTER TABLE arbiter_bbva.coverage
    DROP CONSTRAINT IF EXISTS coverage_settlement_basis_check;
ALTER TABLE arbiter_bbva.coverage
    ADD CONSTRAINT coverage_settlement_basis_check
        CHECK (settlement_basis IN ('SUM_INSURED', 'LESSER_OF_SUM_AND_REPLACEMENT'));

ALTER TABLE arbiter_provincia.coverage
    DROP CONSTRAINT IF EXISTS coverage_settlement_basis_check;
ALTER TABLE arbiter_provincia.coverage
    ADD CONSTRAINT coverage_settlement_basis_check
        CHECK (settlement_basis IN ('SUM_INSURED', 'LESSER_OF_SUM_AND_REPLACEMENT'));

-- Las coberturas semilla (ids 1 y 2, "Robo de celular" y "Hurto", que crea
-- db/init-multitenant.sql) sí se alinean con lo que ahora siembra ese script: si
-- no, una base migrada y una recién creada liquidarían distinto el mismo
-- siniestro, y ese desvío no lo detecta scripts/check-schema-consistency.py
-- porque compara estructura, no datos.
--
-- Robo y Hurto son pérdida total y liquidan como el manual de Celulares: suma
-- asegurada menos franquicia menos las cuotas que restan del año, porque la
-- póliza se extingue con el siniestro. El 50% del segundo evento sale de las
-- condiciones particulares ("dos eventos por año... segundo hasta un 50%"), y
-- Hurto no lo lleva porque admite un solo evento anual.
--
-- Solo esas dos, por id: una cobertura que el referente haya creado después no
-- la modeló nadie acá, y prenderle deducciones a ciegas le cambiaría el monto a
-- siniestros que no miramos.
UPDATE arbiter_bbva.coverage
   SET deduct_pending_installments = TRUE,
       deduct_overdue_balance      = TRUE,
       second_event_percentage     = CASE WHEN id = 1 THEN 50.00 ELSE NULL END
 WHERE id IN (1, 2) AND name IN ('Robo de celular', 'Hurto');

UPDATE arbiter_provincia.coverage
   SET deduct_pending_installments = TRUE,
       deduct_overdue_balance      = TRUE,
       second_event_percentage     = CASE WHEN id = 1 THEN 50.00 ELSE NULL END
 WHERE id IN (1, 2) AND name IN ('Robo de celular', 'Hurto');

-- "Daño accidental" (Tecnología Portátil) liquida por el menor entre suma
-- asegurada y valor de reposición: lo exige el art. 7, Bases de Indemnización,
-- de la cláusula 340. No descuenta cuotas a vencer — una reparación no extingue
-- la póliza.
UPDATE arbiter_provincia.coverage
   SET settlement_basis            = 'LESSER_OF_SUM_AND_REPLACEMENT',
       second_event_percentage     = 50.00,
       deduct_pending_installments = FALSE,
       deduct_overdue_balance      = TRUE
 WHERE name = 'Daño accidental';

-- ─── 2 · policy_snapshot: lo que la cuenta necesita congelado ────────────────
-- Todo nullable: los snapshots que ya existen se tomaron antes de que estos
-- campos existieran y no hay forma honesta de completarlos hacia atrás. Un
-- expediente viejo se liquida con lo que haya, y lo que falte se ve en pantalla
-- como faltante en vez de aparecer como un cero que nadie midió.
ALTER TABLE arbiter_bbva.policy_snapshot
    ADD COLUMN IF NOT EXISTS effective_to       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS installment_amount NUMERIC(15,2),
    ADD COLUMN IF NOT EXISTS overdue_balance    NUMERIC(15,2),
    ADD COLUMN IF NOT EXISTS events_in_year     INTEGER;

ALTER TABLE arbiter_provincia.policy_snapshot
    ADD COLUMN IF NOT EXISTS effective_to       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS installment_amount NUMERIC(15,2),
    ADD COLUMN IF NOT EXISTS overdue_balance    NUMERIC(15,2),
    ADD COLUMN IF NOT EXISTS events_in_year     INTEGER;

-- ─── 3 · case_settlement: la liquidación ────────────────────────────────────
-- Ver el comentario largo en db/init-multitenant.sql. Lo que importa acá:
-- guarda las ENTRADAS además del resultado, así rehacer la cuenta meses después
-- da lo mismo aunque hayan cambiado la póliza o los parámetros del referente; y
-- calculated_amount / settled_amount separados dejan ver el ajuste manual en vez
-- de que el ajuste se coma la propuesta original.
CREATE TABLE IF NOT EXISTS arbiter_bbva.case_settlement (
    id                          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    case_id                     BIGINT        NOT NULL REFERENCES arbiter_bbva.cases(id),
    formula                     VARCHAR(20)   NOT NULL DEFAULT 'TOTAL_LOSS',
    sum_insured                 NUMERIC(15,2) NOT NULL,
    settlement_basis            VARCHAR(30)   NOT NULL,
    replacement_value           NUMERIC(15,2),
    deductible_rate             NUMERIC(5,2),
    event_ordinal               INTEGER       NOT NULL DEFAULT 1,
    event_percentage            NUMERIC(5,2)  NOT NULL DEFAULT 100,
    pending_installments        INTEGER       NOT NULL DEFAULT 0,
    installment_amount          NUMERIC(15,2),
    deductible_amount           NUMERIC(15,2) NOT NULL DEFAULT 0,
    pending_installments_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
    overdue_balance_amount      NUMERIC(15,2) NOT NULL DEFAULT 0,
    calculated_amount           NUMERIC(15,2) NOT NULL,
    settled_amount              NUMERIC(15,2) NOT NULL,
    adjustment_reason           TEXT,
    coverage_id                 BIGINT        NOT NULL REFERENCES arbiter_bbva.coverage(id),
    policy_snapshot_id          BIGINT        REFERENCES arbiter_bbva.policy_snapshot(id),
    analyst_id                  BIGINT        NOT NULL REFERENCES arbiter_bbva.claims_analyst(id),
    calculated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    confirmed_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT case_settlement_case_unique UNIQUE (case_id),
    CONSTRAINT case_settlement_formula_check CHECK (formula IN ('TOTAL_LOSS')),
    CONSTRAINT case_settlement_basis_check
        CHECK (settlement_basis IN ('SUM_INSURED', 'LESSER_OF_SUM_AND_REPLACEMENT'))
);

CREATE TABLE IF NOT EXISTS arbiter_provincia.case_settlement (
    id                          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    case_id                     BIGINT        NOT NULL REFERENCES arbiter_provincia.cases(id),
    formula                     VARCHAR(20)   NOT NULL DEFAULT 'TOTAL_LOSS',
    sum_insured                 NUMERIC(15,2) NOT NULL,
    settlement_basis            VARCHAR(30)   NOT NULL,
    replacement_value           NUMERIC(15,2),
    deductible_rate             NUMERIC(5,2),
    event_ordinal               INTEGER       NOT NULL DEFAULT 1,
    event_percentage            NUMERIC(5,2)  NOT NULL DEFAULT 100,
    pending_installments        INTEGER       NOT NULL DEFAULT 0,
    installment_amount          NUMERIC(15,2),
    deductible_amount           NUMERIC(15,2) NOT NULL DEFAULT 0,
    pending_installments_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
    overdue_balance_amount      NUMERIC(15,2) NOT NULL DEFAULT 0,
    calculated_amount           NUMERIC(15,2) NOT NULL,
    settled_amount              NUMERIC(15,2) NOT NULL,
    adjustment_reason           TEXT,
    coverage_id                 BIGINT        NOT NULL REFERENCES arbiter_provincia.coverage(id),
    policy_snapshot_id          BIGINT        REFERENCES arbiter_provincia.policy_snapshot(id),
    analyst_id                  BIGINT        NOT NULL REFERENCES arbiter_provincia.claims_analyst(id),
    calculated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    confirmed_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT case_settlement_case_unique UNIQUE (case_id),
    CONSTRAINT case_settlement_formula_check CHECK (formula IN ('TOTAL_LOSS')),
    CONSTRAINT case_settlement_basis_check
        CHECK (settlement_basis IN ('SUM_INSURED', 'LESSER_OF_SUM_AND_REPLACEMENT'))
);

-- ─── 4 · BD Aseguradora: importe de cada cuota del premio ───────────────────
ALTER TABLE aseguradora_bbva.poliza
    ADD COLUMN IF NOT EXISTS importe_cuota NUMERIC(38,2);
ALTER TABLE aseguradora_provincia.poliza
    ADD COLUMN IF NOT EXISTS importe_cuota NUMERIC(38,2);

-- Mismo criterio que db/seed-demo.sql: con cuotas impagas manda el saldo real,
-- para que la cuota y la deuda de una misma póliza no se contradigan; sin deuda,
-- el 2% mensual de la suma asegurada, que es lo que dan las dos pólizas BBVA de
-- referencia ($3.606,53 sobre $180.000 en Celulares, $1.872,11 sobre $86.500 en
-- Tecnología Portátil).
UPDATE aseguradora_bbva.poliza p
   SET importe_cuota = CASE
           WHEN p.cuotas_impagas > 0 AND p.saldo_deuda > 0
               THEN ROUND(p.saldo_deuda / p.cuotas_impagas, 2)
           ELSE ROUND(COALESCE(c.suma_asegurada, 0) * 0.02, 2)
       END
  FROM (SELECT poliza_id, MAX(suma_asegurada) AS suma_asegurada
          FROM aseguradora_bbva.cobertura GROUP BY poliza_id) c
 WHERE c.poliza_id = p.id AND p.importe_cuota IS NULL;

UPDATE aseguradora_provincia.poliza p
   SET importe_cuota = CASE
           WHEN p.cuotas_impagas > 0 AND p.saldo_deuda > 0
               THEN ROUND(p.saldo_deuda / p.cuotas_impagas, 2)
           ELSE ROUND(COALESCE(c.suma_asegurada, 0) * 0.02, 2)
       END
  FROM (SELECT poliza_id, MAX(suma_asegurada) AS suma_asegurada
          FROM aseguradora_provincia.cobertura GROUP BY poliza_id) c
 WHERE c.poliza_id = p.id AND p.importe_cuota IS NULL;

COMMIT;

-- Verificación:
-- SELECT table_schema, column_name FROM information_schema.columns
--  WHERE table_name = 'coverage' AND column_name LIKE '%settlement%' OR column_name LIKE 'deduct_%';
-- SELECT table_schema FROM information_schema.tables WHERE table_name = 'case_settlement';
-- SELECT numero, cuotas_impagas, saldo_deuda, importe_cuota FROM aseguradora_bbva.poliza ORDER BY id;
