-- 2026-09-01 · aseguradora_*.siniestro_historico.cobertura_id: the sum insured belongs to the coverage,
-- so only what was settled against that coverage consumes it. Nullable: history that cannot be assigned
-- is skipped, never charged to the wrong one. Best-effort backfill by name. Idempotent.

BEGIN;

DO $$
DECLARE
    insurer_db TEXT;
BEGIN
    FOR insurer_db IN
        SELECT replace(schema_name, 'arbiter_', 'aseguradora_') FROM arbiter_common.insurer
    LOOP
        CONTINUE WHEN to_regclass(insurer_db || '.siniestro_historico') IS NULL;

        EXECUTE format(
            'ALTER TABLE %I.siniestro_historico ADD COLUMN IF NOT EXISTS cobertura_id BIGINT',
            insurer_db);

        -- The foreign key apart from ADD COLUMN, so a rerun does not fail on a duplicate.
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.table_constraints
             WHERE table_schema = insurer_db
               AND table_name = 'siniestro_historico'
               AND constraint_name = 'siniestro_historico_cobertura_fk'
        ) THEN
            EXECUTE format($ddl$
                ALTER TABLE %I.siniestro_historico
                  ADD CONSTRAINT siniestro_historico_cobertura_fk
                  FOREIGN KEY (cobertura_id) REFERENCES %I.cobertura(id)
            $ddl$, insurer_db, insurer_db);
        END IF;

        -- Backfill: the coverage of the same policy whose name matches the cause, only when unambiguous.
        EXECUTE format($dml$
            UPDATE %I.siniestro_historico h
               SET cobertura_id = c.id
              FROM %I.cobertura c
             WHERE c.poliza_id = h.poliza_id
               AND h.cobertura_id IS NULL
               AND c.nombre = CASE h.causa
                                  WHEN 'Robo en vía pública' THEN 'Robo de celular'
                                  WHEN 'Hurto'               THEN 'Hurto'
                                  WHEN 'Daño accidental'     THEN 'Daño accidental'
                                  WHEN 'Rotura accidental'   THEN 'Daño accidental'
                              END
        $dml$, insurer_db, insurer_db);
    END LOOP;
END $$;

COMMIT;

-- Check: unassigned rows are the ones the rule will skip:
-- SELECT h.id, h.causa, c.nombre AS cobertura, h.estado_resolucion, h.monto_indemnizado
--   FROM aseguradora_bbva.siniestro_historico h
--   LEFT JOIN aseguradora_bbva.cobertura c ON c.id = h.cobertura_id
--  ORDER BY h.id;
