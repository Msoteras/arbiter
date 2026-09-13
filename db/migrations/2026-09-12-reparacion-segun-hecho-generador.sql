-- =============================================================================
-- 2026-09-12 · Derivación a reparación según el hecho generador
--
-- Migración de DATOS, sin cambio de esquema: insurer_rule.rule_type no tiene CHECK,
-- así que el tipo nuevo REPAIR_DERIVATION entra como filas. No hace falta rebuildear
-- nada para aplicarla, y el código viejo simplemente no la lee.
--
-- Hasta ahora el botón "Derivar a servicio técnico" aparecía en cualquier expediente
-- con un servicio técnico cargado para el ramo, incluso en un robo: no hay nada que
-- reparar en un equipo que no está. La regla dice, por ramo, qué hechos generadores
-- admiten reparación. Opt-in igual que el peritaje: sin regla, no se ofrece.
--
-- Carga inicial (a confirmar con el equipo, se cambia editando la fila):
--   · Celulares            → Rotura accidental, Caída
--   · Tecnología Portátil  → Daño accidental
--
-- Los hechos se buscan por NOMBRE y no por id, para no depender de la numeración de
-- cada base. Las tildes van con escapes Unicode (U&'...'): aplicada por un pipe de
-- PowerShell, un literal con tilde llegó una vez como '?'.
--
-- Idempotente: cada fila se inserta solo si no existe, y solo si el ramo tiene
-- alguno de esos hechos (una lista vacía sería una regla mal configurada).
-- =============================================================================

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format($sql$
            INSERT INTO %I.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                         blocks_fast_track, branch_id, coverage_id, configuration)
            SELECT TRUE, '2026-01-01 00:00:00+00',
                   U&'Derivar a reparaci\00F3n: rotura accidental y ca\00EDda', 'REPAIR_DERIVATION',
                   'DERIVAR', 1, FALSE, 1, NULL,
                   jsonb_build_object('claimCauseIds', (
                       SELECT jsonb_agg(id ORDER BY id) FROM arbiter_common.claim_cause
                       WHERE branch_id = 1 AND name IN ('Rotura accidental', U&'Ca\00EDda')))
            WHERE NOT EXISTS (
                    SELECT 1 FROM %I.insurer_rule
                    WHERE rule_type = 'REPAIR_DERIVATION' AND branch_id = 1 AND coverage_id IS NULL)
              AND EXISTS (
                    SELECT 1 FROM arbiter_common.claim_cause
                    WHERE branch_id = 1 AND name IN ('Rotura accidental', U&'Ca\00EDda'))
            $sql$, tenant, tenant);

        EXECUTE format($sql$
            INSERT INTO %I.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                         blocks_fast_track, branch_id, coverage_id, configuration)
            SELECT TRUE, '2026-01-01 00:00:00+00',
                   U&'Derivar a reparaci\00F3n: da\00F1o accidental', 'REPAIR_DERIVATION',
                   'DERIVAR', 1, FALSE, 2, NULL,
                   jsonb_build_object('claimCauseIds', (
                       SELECT jsonb_agg(id ORDER BY id) FROM arbiter_common.claim_cause
                       WHERE branch_id = 2 AND name = U&'Da\00F1o accidental'))
            WHERE NOT EXISTS (
                    SELECT 1 FROM %I.insurer_rule
                    WHERE rule_type = 'REPAIR_DERIVATION' AND branch_id = 2 AND coverage_id IS NULL)
              AND EXISTS (
                    SELECT 1 FROM arbiter_common.claim_cause
                    WHERE branch_id = 2 AND name = U&'Da\00F1o accidental')
            $sql$, tenant, tenant);

    END LOOP;
END $$;

COMMIT;

-- Verificación: una fila por aseguradora y ramo, con los ids de sus hechos.
SELECT 'bbva' AS tenant, branch_id, name, configuration
  FROM arbiter_bbva.insurer_rule WHERE rule_type = 'REPAIR_DERIVATION'
UNION ALL
SELECT 'provincia', branch_id, name, configuration
  FROM arbiter_provincia.insurer_rule WHERE rule_type = 'REPAIR_DERIVATION'
ORDER BY 1, 2;
