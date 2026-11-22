-- =============================================================================
-- Deletes the cases of the clean test insureds. Every filed case counts as a previous claim and
-- trips the Fast Track cap, so a fixture run twice stops matching its esperado.json.
--   · Valentín Aguirre  DNI 38.614.270  BBVA       — the mutations (docs/postman/test-docs/mutaciones/)
--                                       BBVA       — the second case's backup (…/demo/caso2/)
--                                       Provincia  — his earlier Provincia cases
--   · Camila Ferreyra   DNI 38.412.905  BBVA       — her set and history sequence (…/camila/)
--   · Sofía Montenegro  DNI 40.218.736  BBVA       — the presentation's Fast Track (…/demo/bbva/)
--   · Tomás Ibarra      DNI 37.905.162  BBVA       — the presentation's second case (…/demo/tomas/)
--
-- When: before EACH mutation; for Camila, once before step 1 and never between steps (the
-- history is what that sequence tests); for the demo, before loading its case again.
-- See docs/postman/test-docs/README.md, §9, §10 and §12.
--
-- The DNIs are fixed on purpose: a script that deletes from the shared database must not take
-- who to delete as an argument. Insured, accounts, policies and aseguradora_*.* stay.
--
-- If it fails on image_analysis.similar_document_id, another case's photo was matched to one of
-- these documents; that is not forced (it would edit another case's forensic analysis).
--
-- Usage (credentials via PGHOST/PGUSER/...; see scripts/db-railway-migrate.py):
--   psql -f scripts/reset-asegurados-de-prueba.sql
-- =============================================================================

BEGIN;

DO $$
DECLARE
    fixture RECORD;
    insured_id   BIGINT;
    case_ids     BIGINT[];
    class_ids    BIGINT[];
    snap_ids     BIGINT[];
    removed      INT;
BEGIN
    FOR fixture IN
        SELECT * FROM (VALUES ('arbiter_bbva',      '38.614.270'),
                              ('arbiter_bbva',      '38.412.905'),
                              ('arbiter_bbva',      '40.218.736'),
                              ('arbiter_bbva',      '37.905.162'),
                              ('arbiter_provincia', '38.614.270')) AS f (tenant, dni)
    LOOP
        EXECUTE format('SELECT id FROM %I.insured WHERE dni = $1', fixture.tenant)
           INTO insured_id USING fixture.dni;
        IF insured_id IS NULL THEN
            RAISE NOTICE 'El asegurado % todavía no existe en % (¿corrió el alta masiva?). Nada que borrar.',
                fixture.dni, fixture.tenant;
            CONTINUE;
        END IF;

        EXECUTE format(
            'SELECT coalesce(array_agg(id), ''{}''),
                    coalesce(array_agg(classification_id) FILTER (WHERE classification_id IS NOT NULL), ''{}''),
                    coalesce(array_agg(policy_snapshot_id) FILTER (WHERE policy_snapshot_id IS NOT NULL), ''{}'')
               FROM %I.cases WHERE insured_id = $1', fixture.tenant)
           INTO case_ids, class_ids, snap_ids USING insured_id;

        IF cardinality(case_ids) = 0 THEN
            RAISE NOTICE 'El asegurado % no tiene expedientes en %. Nada que borrar.', fixture.dni, fixture.tenant;
            CONTINUE;
        END IF;

        -- The only child of cases without ON DELETE CASCADE.
        EXECUTE format('DELETE FROM %I.case_settlement WHERE case_id = ANY ($1)', fixture.tenant) USING case_ids;

        -- A decided case's classification points at its llm_analysis, which the cases delete
        -- cascades to: the classification goes first.
        EXECUTE format('UPDATE %I.cases SET classification_id = NULL WHERE id = ANY ($1)', fixture.tenant)
           USING case_ids;
        EXECUTE format('DELETE FROM %I.case_classification WHERE id = ANY ($1)', fixture.tenant) USING class_ids;

        -- Cascades to documents (and their extraction/image analyses), status history, messages,
        -- LLM analysis, risk analysis, rule results, notifications, expert assessments and fraud
        -- records of these cases.
        EXECUTE format('DELETE FROM %I.cases WHERE id = ANY ($1)', fixture.tenant) USING case_ids;
        GET DIAGNOSTICS removed = ROW_COUNT;

        -- What cases pointed at, now unreferenced: one policy snapshot per case.
        EXECUTE format(
            'DELETE FROM %1$I.policy_snapshot s
              WHERE s.id = ANY ($1)
                AND NOT EXISTS (SELECT 1 FROM %1$I.cases c WHERE c.policy_snapshot_id = s.id)
                AND NOT EXISTS (SELECT 1 FROM %1$I.case_settlement x WHERE x.policy_snapshot_id = s.id)',
            fixture.tenant) USING snap_ids;

        EXECUTE format('UPDATE %I.insured SET case_count = 0 WHERE id = $1', fixture.tenant) USING insured_id;

        RAISE NOTICE 'Asegurado % (%): % expediente(s) borrado(s) (ids %).',
            fixture.dni, fixture.tenant, removed, case_ids;
    END LOOP;
END $$;

COMMIT;
