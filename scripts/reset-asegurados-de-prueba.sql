-- =============================================================================
-- Deletes the cases of BBVA's clean test insured. Every filed case counts as a previous claim
-- and trips the Fast Track cap, so a fixture run twice stops matching its esperado.json.
--   · Valentín Aguirre  DNI 38.614.270  — the mutations (docs/postman/test-docs/mutaciones/)
--   · Camila Ferreyra   DNI 38.412.905  — her set and history sequence (…/camila/)
--
-- When: before EACH mutation; for Camila, once before step 1 and never between steps (the
-- history is what that sequence tests). See docs/postman/test-docs/README.md, §9 and §10.
--
-- The DNIs are fixed on purpose: a script that deletes from the shared database must not take
-- who to delete as an argument. Insured, accounts, policies and aseguradora_bbva.* stay.
--
-- If it fails on image_analysis.similar_document_id, another case's photo was matched to one of
-- these documents; that is not forced (it would edit another case's forensic analysis).
--
-- Usage (credentials via PGHOST/PGUSER/...; see scripts/db-railway-migrate.py):
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
