-- =============================================================================
-- 2026-09-05 · Los criterios del carril rápido, y la cobertura que le faltaba
--              al ramo Celulares de BBVA (H0038)
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed. Lo mismo que ya
-- quedó en db/init-multitenant.sql y db/seed-demo.sql para una base nueva.
--
-- Qué arregla, en orden:
--
--   1. Los umbrales de Fast Track no estaban en ninguna tabla. Las dos filas
--      FAST_TRACK del seed venían sin coverage_id y sin configuration, y
--      FastTrackRuleService las busca por (ramo, cobertura): no las encontraba
--      nunca. El referente veía la pantalla de Fast Track en blanco y los
--      umbrales que de verdad decidían salían del baseline de MockRulesAdapter
--      — de código, contra la decisión #12 (reglas en BD, sin redeploy).
--
--   2. Los rule_result viejos guardaban el gate como rule_type 'FAST_TRACK' con
--      un valor opaco ('0.219', 'AL_DIA'). Ahora el motor escribe un tipo por
--      criterio (FT_*) con el valor comparado, y la pantalla los muestra en una
--      card propia. Las filas viejas se reexpresan para que no queden en la
--      tabla de reglas duras diciendo '0.219'.
--
--   3. En el ramo Celulares de BBVA, los hechos generadores Rotura accidental
--      (1) y Caída (4) no los cubría NINGUNA cobertura: existían solo Robo y
--      Hurto, cuyas listas negras son [1,3,4] y [1,2,4]. No era una decisión de
--      producto — la agenda documental ya tiene cargados los requisitos de los
--      dos, el selector del wizard filtra por esas listas y por lo tanto no
--      podía ofrecerlos. Se agrega la cobertura Daño accidental al ramo, en las
--      pólizas Premium, y los expedientes de rotura pasan a colgar de ella.
--
--   4. En Provincia, la póliza POL-CEL-2026-905 no tenía contratada la
--      cobertura de Hurto y su expediente de hurto colgaba de la de Robo, que
--      lo excluye.
--
-- Lo que esta migración NO hace, a propósito: agregar Robo y Hurto al ramo
-- Tecnología Portátil de Provincia, que tiene el mismo agujero. No hay ningún
-- expediente afectado y las sumas aseguradas son un dato de negocio que hay que
-- decidir — ver docs/temas-a-discutir.md.
--
-- Ramo 1 · Celulares      → claim_cause 1 Rotura accidental, 2 Robo en vía
--                           pública, 3 Hurto, 4 Caída
-- Ramo 2 · Tec. Portátil  → claim_cause 6 Daño accidental, 7 Robo, 8 Hurto
--
-- Idempotente: se puede correr más de una vez sin romper nada.
-- =============================================================================

BEGIN;

-- ─── 1 · Umbrales de Fast Track como configuración, una fila por cobertura ────
-- Los valores son los del baseline de MockRulesAdapter, para que la BD diga lo
-- mismo que venía decidiendo el fallback y el cambio no altere ningún veredicto.
--
-- Sin requiredDocumentTypes: qué documentos exige el ramo ya vive en
-- document_requirement (la agenda documental), que se valida en el alta.
-- Repetirlo acá hace que el gate lo vuelva a evaluar contra los adjuntos que
-- alcanzó a leer y frene Fast Tracks por documentación que el expediente sí
-- tiene.
DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN
        SELECT schema_name FROM arbiter_common.insurer
    LOOP
        -- Las filas viejas sin cobertura no las lee nadie. No se borran: hay
        -- rule_result apuntándoles (FK), y esas filas son historial.
        EXECUTE format($dml$
            UPDATE %I.insurer_rule
               SET active = FALSE,
                   name   = name || ' (obsoleta: reemplazada por la config por cobertura)'
             WHERE rule_type = 'FAST_TRACK'
               AND coverage_id IS NULL
               AND active
        $dml$, tenant);

        -- Una fila por cobertura, con el tope que le corresponde. El nombre de la
        -- cobertura es la llave y no el id: los ids son por esquema y no todos los
        -- tenants tienen las mismas coberturas.
        EXECUTE format($dml$
            INSERT INTO %I.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                         blocks_fast_track, branch_id, coverage_id, configuration)
            SELECT TRUE, '2026-01-01 00:00:00+00', 'Fast Track — ' || c.name,
                   'FAST_TRACK', 'APROBAR', 1, FALSE, c.branch_id, c.id,
                   CASE
                       WHEN c.name = 'Robo de celular' THEN
                           '{"maxClaimedAmountRatio":0.5,"maxPriorClaims":0,"requiresUpToDatePolicy":true,
                             "criteria":["Primer siniestro del asegurado",
                                         "Monto reclamado inferior al 50%% de la suma asegurada",
                                         "Póliza al día con sus pagos"]}'
                       WHEN c.name = 'Hurto' THEN
                           '{"maxClaimedAmountRatio":0.3,"maxPriorClaims":0,"requiresUpToDatePolicy":true,
                             "criteria":["Primer siniestro del asegurado",
                                         "Monto reclamado inferior al 30%% de la suma asegurada",
                                         "Póliza al día con sus pagos"]}'
                       -- Daño accidental de Tecnología Portátil: el tope es más alto
                       -- porque reparar un equipo portátil cuesta una fracción grande de
                       -- una suma asegurada chica, y con el 50%% de robo quedarían fuera
                       -- del carril rápido casi todos los casos del ramo.
                       WHEN b.name = 'Tecnología Portátil' THEN
                           '{"maxClaimedAmountRatio":0.6,"maxPriorClaims":0,"requiresUpToDatePolicy":true,
                             "criteria":["Primer siniestro del asegurado",
                                         "Monto reclamado inferior al 60%% de la suma asegurada",
                                         "Póliza al día con sus pagos"]}'
                       ELSE
                           '{"maxClaimedAmountRatio":0.5,"maxPriorClaims":0,"requiresUpToDatePolicy":true,
                             "criteria":["Primer siniestro del asegurado",
                                         "Monto reclamado inferior al 50%% de la suma asegurada",
                                         "Póliza al día con sus pagos"]}'
                   END::jsonb
              FROM %I.coverage c
              JOIN arbiter_common.branch b ON b.id = c.branch_id
             WHERE NOT EXISTS (
                   SELECT 1 FROM %I.insurer_rule r
                    WHERE r.rule_type = 'FAST_TRACK' AND r.coverage_id = c.id)
        $dml$, tenant, tenant, tenant);
    END LOOP;
END $$;

-- ─── 2 · Los rule_result viejos del gate, reexpresados por criterio ───────────
-- Un tipo por criterio y el valor comparado, como los escribe FastTrackValidator.
-- rule_id pasa a NULL porque los umbrales son configuración, no una regla
-- evaluable con id propio — es lo que escribe el motor hoy.
--
-- Solo se tocan las filas que se pueden reexpresar sin adivinar: un valor que es
-- un número (la relación monto/suma) y los dos literales de estado de pago.
-- Cualquier otra cosa queda como está: preferimos una fila vieja legible a una
-- inventada.
DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN
        SELECT schema_name FROM arbiter_common.insurer
    LOOP
        -- Monto reclamado sobre la suma asegurada. El tope sale de la configuración
        -- de la cobertura del expediente, que la parte 1 ya dejó cargada.
        EXECUTE format($dml$
            UPDATE %I.rule_result rr
               SET rule_type = 'FT_AMOUNT_RATIO',
                   rule_id   = NULL,
                   evaluated_value =
                       'ratio=' || to_char(round(rr.evaluated_value::numeric * 100, 1), 'FM990.0') || '%%'
                       || ' max=' || to_char(
                              round(((r.configuration->>'maxClaimedAmountRatio')::numeric) * 100, 1),
                              'FM990.0') || '%%'
              FROM %I.cases c
              JOIN %I.insurer_rule r
                ON r.rule_type = 'FAST_TRACK' AND r.coverage_id = c.coverage_id
             WHERE rr.case_id = c.id
               AND rr.rule_type = 'FAST_TRACK'
               AND rr.evaluated_value ~ '^[0-9]*\.?[0-9]+$'
        $dml$, tenant, tenant, tenant);

        -- Estado de pago de la póliza.
        EXECUTE format($dml$
            UPDATE %I.rule_result
               SET rule_type = 'FT_POLICY_UP_TO_DATE',
                   rule_id   = NULL,
                   evaluated_value = CASE WHEN evaluated_value = 'AL_DIA'
                                          THEN 'upToDate=true' ELSE 'upToDate=false' END
             WHERE rule_type = 'FAST_TRACK'
               AND evaluated_value IN ('AL_DIA', 'SUSPENDIDA', 'RESUELTA')
        $dml$, tenant);

        -- De paso, las filas que quedaron con el tipo de la lista blanca. La
        -- migración 2026-09-01-coverage-exclusion-viva.sql convirtió las reglas
        -- (insurer_rule) pero no los resultados ya escritos, y COVERAGE_INCLUSION
        -- no existe en RuleType: la pantalla los muestra con el literal crudo.
        -- El significado de la fila no cambia —el hecho generador estaba excluido,
        -- por eso FAIL—, solo el tipo y el formato del valor, que pasa a ser el que
        -- escribe CoverageRuleEvaluator (con el id, porque los nombres se repiten
        -- entre ramos).
        EXECUTE format($dml$
            UPDATE %I.rule_result rr
               SET rule_type = 'COVERAGE_EXCLUSION',
                   evaluated_value = 'claimCause=' || cc.name || ' (id=' || cc.id || ')'
              FROM %I.cases c
              JOIN arbiter_common.claim_cause cc ON cc.id = c.claim_cause_id
             WHERE rr.case_id = c.id
               AND rr.rule_type = 'COVERAGE_INCLUSION'
               -- Some rows already carry CoverageRuleEvaluator's format and only the type is stale.
               AND rr.evaluated_value IN (cc.name, 'claimCause=' || cc.name || ' (id=' || cc.id || ')')
        $dml$, tenant, tenant);

        -- Cualquier COVERAGE_INCLUSION que no se haya podido reexpresar queda como
        -- está: sin el nombre del hecho generador no hay forma de reconstruir el
        -- valor sin inventarlo.
    END LOOP;
END $$;

-- ─── 3 · BBVA: la cobertura Daño accidental del ramo Celulares ───────────────
-- Solo BBVA: las coberturas son configuración de cada aseguradora, y lo de
-- Provincia está pendiente de una decisión de negocio (temas-a-discutir.md).
DO $$
DECLARE
    tenant     TEXT := 'arbiter_bbva';
    aseg       TEXT := 'aseguradora_bbva';
    celulares  BIGINT;
    nueva      BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM arbiter_common.insurer WHERE schema_name = tenant) THEN
        RAISE NOTICE 'No existe el esquema %, se saltea la parte 3', tenant;
        RETURN;
    END IF;

    SELECT id INTO celulares FROM arbiter_common.branch WHERE name = 'Celulares';

    -- a) La cobertura. Franquicia 20% contra el 10% de robo, que es el diferencial
    --    habitual del daño accidental.
    EXECUTE format($dml$
        INSERT INTO %I.coverage (name, description, report_deadline_hours, max_events_per_year,
                                 covers_family_group, deductible, claim_exhausts_coverage,
                                 is_individual, waiting_period_days, branch_id)
        SELECT 'Daño accidental', 'Cobertura por rotura o caída accidental del equipo',
               72, 2, FALSE, 20.00, FALSE, TRUE, 30, $1
         WHERE NOT EXISTS (SELECT 1 FROM %I.coverage
                            WHERE name = 'Daño accidental' AND branch_id = $1)
    $dml$, tenant, tenant) USING celulares;

    EXECUTE format($q$SELECT id FROM %I.coverage WHERE name = 'Daño accidental' AND branch_id = $1$q$,
                   tenant) INTO nueva USING celulares;

    -- Damage settles as a repair (2026-09-06-formula-de-reparacion.sql). That migration applies it
    -- by name, so it only reaches this coverage if it runs after this one; on a database where it
    -- already ran, the coverage would be born TOTAL_LOSS. The column may not exist yet either,
    -- hence the guard.
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = tenant AND table_name = 'coverage'
                  AND column_name = 'settlement_formula') THEN
        EXECUTE format($dml$UPDATE %I.coverage SET settlement_formula = 'REPAIR' WHERE id = $1$dml$,
                       tenant) USING nueva;
    END IF;

    -- b) Sus reglas, con la misma forma que las de las otras dos coberturas.
    --    Sin POLICE_DEADLINE: una rotura accidental no tiene denuncia policial que
    --    presentar, así que la fila sería una regla imposible de cumplir.
    --    El FAST_TRACK ya lo insertó la parte 1 si la cobertura existía; si la
    --    acabamos de crear, entra acá.
    EXECUTE format($dml$
        INSERT INTO %I.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                     blocks_fast_track, branch_id, coverage_id, configuration)
        SELECT * FROM (VALUES
            (TRUE, '2026-01-01 00:00:00+00'::timestamptz,
             'La cobertura de daño accidental solo cubre rotura y caída',
             'COVERAGE_EXCLUSION', 'RECHAZAR', 1, TRUE, $1::bigint, $2::bigint,
             '{"excludedClaimCauseIds":[2,3]}'::jsonb),
            (TRUE, '2026-01-01 00:00:00+00'::timestamptz,
             'Fast Track — Daño accidental', 'FAST_TRACK', 'APROBAR', 1, FALSE, $1, $2,
             '{"maxClaimedAmountRatio":0.5,"maxPriorClaims":0,"requiresUpToDatePolicy":true,
               "criteria":["Primer siniestro del asegurado",
                           "Monto reclamado inferior al 50%% de la suma asegurada",
                           "Póliza al día con sus pagos"]}'::jsonb),
            (TRUE, '2026-01-01 00:00:00+00'::timestamptz,
             'Carencia de la cobertura (daño accidental)',
             'WAITING_PERIOD', 'DERIVAR', 2, TRUE, $1, $2, '{}'::jsonb),
            (TRUE, '2026-01-01 00:00:00+00'::timestamptz,
             'Plazo de denuncia a la aseguradora (daño accidental)',
             'REPORT_DEADLINE', 'DERIVAR', 3, TRUE, $1, $2, '{}'::jsonb),
            (TRUE, '2026-01-01 00:00:00+00'::timestamptz,
             'Tope de eventos por año (daño accidental)',
             'MAX_EVENTS_YEAR', 'DERIVAR', 5, TRUE, $1, $2, '{}'::jsonb)
        ) AS nueva(active, valid_from, name, rule_type, effect, priority,
                   blocks_fast_track, branch_id, coverage_id, configuration)
         WHERE NOT EXISTS (
               SELECT 1 FROM %I.insurer_rule r
                WHERE r.coverage_id = $2 AND r.rule_type = nueva.rule_type)
    $dml$, tenant, tenant) USING celulares, nueva;

    -- c) La cobertura entra en las pólizas Premium, por la misma suma que robo
    --    (es el mismo equipo). Las Básico no la traen: a esos asegurados el wizard
    --    sigue sin ofrecerles rotura ni caída, y eso ahora sí es producto y no un
    --    agujero.
    EXECUTE format($dml$
        INSERT INTO %I.policy_coverage (policy_id, coverage_id, display_order, sum_insured, deductible_pct)
        SELECT p.id, $1, 3, robo.sum_insured, 20.00
          FROM %I.policy p
          JOIN %I.policy_coverage robo ON robo.policy_id = p.id
          JOIN %I.coverage rc ON rc.id = robo.coverage_id AND rc.name = 'Robo de celular'
         WHERE p.product ILIKE '%%Premium%%'
           AND NOT EXISTS (SELECT 1 FROM %I.policy_coverage pc
                            WHERE pc.policy_id = p.id AND pc.coverage_id = $1)
    $dml$, tenant, tenant, tenant, tenant, tenant) USING nueva;

    -- d) El espejo en la BD Aseguradora, que es de donde el motor lee la suma.
    EXECUTE format($dml$
        INSERT INTO %I.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct)
        SELECT pz.id, 3, 'Daño accidental', robo.suma_asegurada, 20.00
          FROM %I.poliza pz
          JOIN %I.cobertura robo ON robo.poliza_id = pz.id AND robo.nombre = 'Robo de celular'
         WHERE pz.rama = 'Celulares'
           AND pz.producto ILIKE '%%Premium%%'
           AND NOT EXISTS (SELECT 1 FROM %I.cobertura c
                            WHERE c.poliza_id = pz.id AND c.nombre = 'Daño accidental')
    $dml$, aseg, aseg, aseg, aseg);

    -- e) Los expedientes de rotura y caída pasan a colgar de la cobertura que los
    --    cubre. Solo si su póliza la tiene contratada: repuntar uno de una póliza
    --    Básica lo dejaría apuntando a una cobertura que no compró.
    EXECUTE format($dml$
        UPDATE %I.cases c
           SET coverage_id = $1
          FROM arbiter_common.claim_cause cc
         WHERE cc.id = c.claim_cause_id
           AND cc.name IN ('Rotura accidental', 'Caída')
           AND c.coverage_id <> $1
           AND EXISTS (SELECT 1 FROM %I.policy_coverage pc
                        WHERE pc.policy_id = c.policy_id AND pc.coverage_id = $1)
    $dml$, tenant, tenant) USING nueva;
END $$;

-- ─── 4 · Provincia: la póliza sin cobertura de hurto y su expediente ─────────
DO $$
DECLARE
    tenant TEXT := 'arbiter_provincia';
    aseg   TEXT := 'aseguradora_provincia';
    poliza CONSTANT TEXT := 'POL-CEL-2026-905';
    hurto  BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM arbiter_common.insurer WHERE schema_name = tenant) THEN
        RAISE NOTICE 'No existe el esquema %, se saltea la parte 4', tenant;
        RETURN;
    END IF;

    EXECUTE format($q$SELECT id FROM %I.coverage WHERE name = 'Hurto'$q$, tenant) INTO hurto;
    IF hurto IS NULL THEN
        RAISE NOTICE 'El tenant % no tiene cobertura de Hurto, se saltea la parte 4', tenant;
        RETURN;
    END IF;

    -- La suma de hurto es la mitad de la de robo, que es el criterio de la
    -- compañía en el resto de sus pólizas.
    EXECUTE format($dml$
        INSERT INTO %I.cobertura (poliza_id, orden, nombre, suma_asegurada, franquicia_pct)
        SELECT pz.id, 2, 'Hurto', round(robo.suma_asegurada / 2, 2), 15.00
          FROM %I.poliza pz
          JOIN %I.cobertura robo ON robo.poliza_id = pz.id AND robo.nombre = 'Robo de celular'
         WHERE pz.numero = $1
           AND NOT EXISTS (SELECT 1 FROM %I.cobertura c
                            WHERE c.poliza_id = pz.id AND c.nombre = 'Hurto')
    $dml$, aseg, aseg, aseg, aseg) USING poliza;

    EXECUTE format($dml$
        INSERT INTO %I.policy_coverage (policy_id, coverage_id, display_order, sum_insured, deductible_pct)
        SELECT p.id, $2, 2, round(robo.sum_insured / 2, 2), 15.00
          FROM %I.policy p
          JOIN %I.policy_coverage robo ON robo.policy_id = p.id
          JOIN %I.coverage rc ON rc.id = robo.coverage_id AND rc.name = 'Robo de celular'
         WHERE p.external_policy_number = $1
           AND NOT EXISTS (SELECT 1 FROM %I.policy_coverage pc
                            WHERE pc.policy_id = p.id AND pc.coverage_id = $2)
    $dml$, tenant, tenant, tenant, tenant, tenant) USING poliza, hurto;

    -- El expediente de hurto que colgaba de la cobertura de robo, que lo excluye.
    EXECUTE format($dml$
        UPDATE %I.cases c
           SET coverage_id = $2
          FROM arbiter_common.claim_cause cc, %I.policy p
         WHERE cc.id = c.claim_cause_id AND cc.name = 'Hurto'
           AND p.id = c.policy_id AND p.external_policy_number = $1
           AND c.coverage_id <> $2
           AND EXISTS (SELECT 1 FROM %I.policy_coverage pc
                        WHERE pc.policy_id = c.policy_id AND pc.coverage_id = $2)
    $dml$, tenant, tenant, tenant) USING poliza, hurto;
END $$;

COMMIT;

-- =============================================================================
-- Opcional · alinear el caso 11 de BBVA con el fixture nuevo
-- =============================================================================
--
-- Esta migración NO toca montos reclamados: un script de datos no debería
-- reescribir lo que alguien denunció, aunque la base sea de demo.
--
-- Pero el caso 11 reclama 470.000 sobre una suma asegurada de 500.000 (94%) y
-- está marcado como Fast Track, que con el tope de 50% de su cobertura no
-- podría haber calificado. Después de esta migración eso se ve: la card de
-- criterios muestra "Cumple" con un 94% contra un tope de 50%. En el seed nuevo
-- el caso quedó en 240.000 (48%). Si querés dejar la base desplegada igual que
-- el fixture, corré esto a mano:
--
-- UPDATE arbiter_bbva.cases SET claimed_amount = 240000.00 WHERE id = 11;
-- UPDATE arbiter_bbva.rule_result SET evaluated_value = 'ratio=48.0% max=50.0%'
--  WHERE case_id = 11 AND rule_type = 'FT_AMOUNT_RATIO';
-- UPDATE arbiter_bbva.risk_analysis
--    SET risk_score = 0.216, risk_band = 'LOW',
--        risk_breakdown = jsonb_set(jsonb_set(jsonb_set(risk_breakdown,
--            '{0,rawScore}', '0.48'), '{0,weightedContribution}', '0.216'),
--            '{0,rationale}', '"Monto reclamado es 48% de la suma asegurada"')
--  WHERE case_id = 11;

-- =============================================================================
-- Verificación (correr después, por esquema)
-- =============================================================================
--
-- 1. Cada cobertura tiene que tener su fila FAST_TRACK con configuration, y no
--    puede quedar ninguna FAST_TRACK activa sin cobertura:
--
-- SELECT c.name, r.active, r.configuration->>'maxClaimedAmountRatio' AS tope
--   FROM arbiter_bbva.coverage c
--   LEFT JOIN arbiter_bbva.insurer_rule r
--          ON r.coverage_id = c.id AND r.rule_type = 'FAST_TRACK'
--  ORDER BY c.id;
-- SELECT count(*) FROM arbiter_bbva.insurer_rule
--  WHERE rule_type = 'FAST_TRACK' AND coverage_id IS NULL AND active;  -- 0
--
-- 2. No puede quedar ningún rule_result con el tipo viejo:
--
-- SELECT rule_type, count(*) FROM arbiter_bbva.rule_result GROUP BY 1 ORDER BY 1;
--
-- 3. Todo hecho generador del ramo tiene que ser cubierto por alguna cobertura
--    (esta es la que encontró el agujero de Rotura accidental y Caída):
--
-- WITH excl AS (
--   SELECT r.coverage_id, (jsonb_array_elements(r.configuration->'excludedClaimCauseIds'))::bigint AS cause
--     FROM arbiter_bbva.insurer_rule r WHERE r.rule_type='COVERAGE_EXCLUSION' AND r.active)
-- SELECT cc.name, coalesce(string_agg(cov.name, ', '), '(NINGUNA)') AS cubierto_por
--   FROM arbiter_common.claim_cause cc
--   LEFT JOIN arbiter_bbva.coverage cov ON cov.branch_id = cc.branch_id
--    AND NOT EXISTS (SELECT 1 FROM excl e WHERE e.coverage_id = cov.id AND e.cause = cc.id)
--  WHERE cc.branch_id = 1 GROUP BY cc.id, cc.name ORDER BY cc.id;
--
-- 4. Ningún expediente puede colgar de una cobertura que excluya su hecho
--    generador, salvo los dos casos de demo de "hurto no cubierto", ni apuntar a
--    una cobertura que su póliza no tenga contratada:
--
-- SELECT c.id, c.claim_cause_id, c.coverage_id
--   FROM arbiter_bbva.cases c JOIN arbiter_bbva.insurer_rule r
--     ON r.rule_type='COVERAGE_EXCLUSION' AND r.active AND r.coverage_id=c.coverage_id
--  WHERE r.configuration->'excludedClaimCauseIds' @> to_jsonb(c.claim_cause_id);
-- SELECT c.id FROM arbiter_bbva.cases c
--  WHERE NOT EXISTS (SELECT 1 FROM arbiter_bbva.policy_coverage pc
--                     WHERE pc.policy_id=c.policy_id AND pc.coverage_id=c.coverage_id);
