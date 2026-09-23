-- =============================================================================
-- Borra los expedientes de los asegurados de prueba limpios de BBVA:
--   · Valentín Aguirre  DNI 38.614.270  — las mutaciones (docs/postman/test-docs/mutaciones/)
--   · Camila Ferreyra   DNI 38.412.905  — su set y la secuencia de historial (…/camila/)
--
-- Para qué: cada caso cargado en Arbiter cuenta como siniestro previo de quien lo cargó
-- (ClassificationOrchestrator.withArbiterAntecedents). Con un solo caso previo ya falla el
-- máximo de siniestros previos del Fast Track, así que un fixture corrido dos veces deja
-- de dar lo que dice su esperado.json.
--
-- Cuándo:
--   · mutaciones: antes de CADA una.
--   · historial de Camila: una vez, antes del paso 1. Nunca entre pasos — el historial es lo
--     que la secuencia prueba.
-- Ver docs/postman/test-docs/README.md, §9 y §10.
--
-- Qué NO toca, a propósito:
--   · a nadie más. Los DNI están fijos acá abajo y no se parametrizan: un script que borra
--     expedientes de la base compartida no puede recibir a quién borrar por argumento.
--     Martina, Roman y Julián quedan afuera: sus casos son historia de las pruebas manuales.
--   · a los asegurados mismos, sus cuentas ni sus pólizas: se crearon una vez y quedan.
--   · aseguradora_bbva.*: la base de la compañía no tiene nada que Arbiter haya escrito.
--
-- Si falla por image_analysis.similar_document_id: la foto de OTRO expediente quedó marcada
-- como parecida a un documento de estos asegurados. No se fuerza (sería editar el análisis
-- forense de otro caso); estos sets no llevan fotos, así que no debería pasar.
--
-- Todo en una transacción: o borra todo el rastro, o nada.
--
-- Uso (credenciales por PGHOST/PGUSER/... — ver scripts/db-railway-migrate.py):
--   psql -f scripts/reset-asegurados-de-prueba.sql
-- =============================================================================

BEGIN;

DO $$
<<reset_ids>>
DECLARE
    fixture_dnis CONSTANT TEXT[] := ARRAY['38.614.270', '38.412.905'];
    fixture_dni  TEXT;
    insured_id   BIGINT;
    case_ids     BIGINT[];
    class_ids    BIGINT[];
    snap_ids     BIGINT[];
    removed      INT;
BEGIN
    FOREACH fixture_dni IN ARRAY fixture_dnis
    LOOP
        SELECT id INTO insured_id FROM arbiter_bbva.insured WHERE dni = fixture_dni;
        IF insured_id IS NULL THEN
            RAISE NOTICE 'El asegurado % todavía no existe en arbiter_bbva (¿corrió el alta masiva?). Nada que borrar.',
                fixture_dni;
            CONTINUE;
        END IF;

        SELECT coalesce(array_agg(id), '{}'),
               coalesce(array_agg(classification_id) FILTER (WHERE classification_id IS NOT NULL), '{}'),
               coalesce(array_agg(policy_snapshot_id) FILTER (WHERE policy_snapshot_id IS NOT NULL), '{}')
          INTO case_ids, class_ids, snap_ids
          FROM arbiter_bbva.cases
         WHERE cases.insured_id = reset_ids.insured_id;

        IF cardinality(case_ids) = 0 THEN
            RAISE NOTICE 'El asegurado % no tiene expedientes. Nada que borrar.', fixture_dni;
            CONTINUE;
        END IF;

        -- The only child of cases without ON DELETE CASCADE.
        DELETE FROM arbiter_bbva.case_settlement WHERE case_id = ANY (case_ids);

        -- Cascades to documents (and their extraction/image analyses), status history, messages,
        -- LLM analysis, risk analysis, rule results, notifications, expert assessments and fraud
        -- records of these cases.
        DELETE FROM arbiter_bbva.cases WHERE id = ANY (case_ids);
        GET DIAGNOSTICS removed = ROW_COUNT;

        -- What cases pointed at, now unreferenced: one classification and one policy snapshot per case.
        DELETE FROM arbiter_bbva.case_classification WHERE id = ANY (class_ids);
        DELETE FROM arbiter_bbva.policy_snapshot s
         WHERE s.id = ANY (snap_ids)
           AND NOT EXISTS (SELECT 1 FROM arbiter_bbva.cases c WHERE c.policy_snapshot_id = s.id)
           AND NOT EXISTS (SELECT 1 FROM arbiter_bbva.case_settlement x WHERE x.policy_snapshot_id = s.id);

        UPDATE arbiter_bbva.insured SET case_count = 0 WHERE id = insured_id;

        RAISE NOTICE 'Asegurado %: % expediente(s) borrado(s) (ids %).', fixture_dni, removed, case_ids;
    END LOOP;
END reset_ids $$;

COMMIT;
