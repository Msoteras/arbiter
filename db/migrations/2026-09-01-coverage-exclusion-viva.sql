-- =============================================================================
-- 2026-09-01 · Las exclusiones de cobertura del seed estaban muertas
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Qué arregla:
--   Las filas de insurer_rule que declaran qué hechos generadores NO cubre cada
--   cobertura estaban cargadas con rule_type = 'COVERAGE_INCLUSION' y una
--   configuration {"includedClaimCauseIds": [...]}. Ese tipo de regla NO EXISTE
--   en el código: RuleType no tiene la constante, y los cuatro lectores
--   (InternalEvaluableRuleService, CoverageRuleEvaluator, RulesServiceClient y
--   el panel del referente) filtran por 'COVERAGE_EXCLUSION'. Resultado: el
--   motor no veía ninguna exclusión y toda cobertura cubría todo — el caso 6 del
--   handoff ("Hurto no cubierto") no se reproducía.
--
--   Se reescriben como lista negra, que es lo que el sistema sí evalúa. El
--   comportamiento buscado es el mismo: cada cobertura cubre un solo hecho
--   generador de su ramo.
--
-- Ramo 1 · Celulares      → claim_cause 1 Rotura accidental, 2 Robo en vía
--                           pública, 3 Hurto, 4 Caída
-- Ramo 2 · Tec. Portátil  → claim_cause 6 Daño accidental, 7 Robo en vía
--                           pública, 8 Hurto
--
-- Idempotente: se puede correr más de una vez sin romper nada.
-- =============================================================================

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN
        SELECT schema_name FROM arbiter_common.insurer
    LOOP
        -- 1. Las filas viejas de lista blanca: se convierten en su equivalente negro.
        --    Robo de celular (coverage 1) cubre solo claim_cause 2 → excluye 1, 3 y 4.
        EXECUTE format($dml$
            UPDATE %I.insurer_rule
               SET rule_type     = 'COVERAGE_EXCLUSION',
                   name          = 'La cobertura de robo solo cubre robo en vía pública',
                   configuration = '{"excludedClaimCauseIds":[1,3,4]}'
             WHERE rule_type = 'COVERAGE_INCLUSION'
               AND coverage_id = 1
        $dml$, tenant);

        --    Hurto (coverage 2) cubre solo claim_cause 3 → excluye 1, 2 y 4.
        EXECUTE format($dml$
            UPDATE %I.insurer_rule
               SET rule_type     = 'COVERAGE_EXCLUSION',
                   name          = 'La cobertura de hurto solo cubre hurto',
                   configuration = '{"excludedClaimCauseIds":[1,2,4]}'
             WHERE rule_type = 'COVERAGE_INCLUSION'
               AND coverage_id = 2
        $dml$, tenant);

        -- 2. Cualquier otra COVERAGE_INCLUSION que haya quedado dando vueltas se desactiva:
        --    el motor no la lee, así que dejarla activa solo confunde a quien mire la tabla.
        EXECUTE format($dml$
            UPDATE %I.insurer_rule SET active = FALSE WHERE rule_type = 'COVERAGE_INCLUSION'
        $dml$, tenant);

        -- 3. Daño accidental (coverage 3, ramo 2) no tenía regla: cubría todo el ramo.
        --    Solo se inserta si esa cobertura existe en este tenant.
        EXECUTE format($dml$
            INSERT INTO %I.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                         blocks_fast_track, branch_id, coverage_id, configuration)
            SELECT TRUE, '2026-01-01 00:00:00+00',
                   'La cobertura de daño accidental solo cubre daño accidental',
                   'COVERAGE_EXCLUSION', 'RECHAZAR', 1, TRUE, 2, c.id,
                   '{"excludedClaimCauseIds":[7,8]}'
              FROM %I.coverage c
             WHERE c.name = 'Daño accidental'
               AND NOT EXISTS (
                   SELECT 1 FROM %I.insurer_rule r
                    WHERE r.coverage_id = c.id AND r.rule_type = 'COVERAGE_EXCLUSION')
        $dml$, tenant, tenant, tenant);
    END LOOP;
END $$;

COMMIT;

-- Verificación (por esquema): cada cobertura tiene que listar los hechos que NO cubre.
-- SELECT c.name, r.rule_type, r.active, r.configuration
--   FROM arbiter_bbva.coverage c
--   LEFT JOIN arbiter_bbva.insurer_rule r ON r.coverage_id = c.id
--    AND r.rule_type = 'COVERAGE_EXCLUSION'
--  ORDER BY c.id;
