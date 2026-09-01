-- =============================================================================
-- 2026-09-01 · El siniestro histórico se imputa a su cobertura
--
-- Migración puntual y NO destructiva, para aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- Qué agrega, en cada esquema de BD Aseguradora:
--   · aseguradora_*.siniestro_historico.cobertura_id
--
-- Por qué: la suma asegurada es de la COBERTURA y no hay tope agregado por
-- póliza (confirmado con la analista, 01/09/2026). Entonces lo que consume el
-- techo de una cobertura es lo que se liquidó contra ESA cobertura. Sin esta
-- columna, CoverageScopeEvaluator.evaluateSumInsuredLimit sumaba todo lo
-- liquidado de la póliza y lo comparaba contra el techo de una sola cobertura:
-- en la póliza 1 del seed, un robo liquidado por 700.000 reportaba la cobertura
-- de hurto (650.000) como agotada sin que se hubiera denunciado un solo hurto.
-- No rechazaba el siniestro —la regla bloquea Fast Track y aporta motivos— pero
-- le mostraba al analista un motivo falso.
--
-- Nullable a propósito: un histórico viejo puede no tener a qué cobertura
-- imputarse, y ahí la regla lo saltea en vez de cargarlo contra la equivocada.
--
-- El backfill imputa por nombre cuando la causa lo determina sin ambigüedad. Lo
-- que no se puede determinar queda en NULL — no se adivina. OJO: que la relación
-- hecho generador ↔ cobertura sea lineal está en duda (charla pendiente con el
-- equipo), así que este backfill es best-effort sobre los ramos de hoy y no una
-- regla general.
--
-- Idempotente: se puede correr más de una vez sin romper nada.
-- =============================================================================

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

        -- La FK aparte del ADD COLUMN para poder repetir el script sin que falle por duplicada.
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

        -- Backfill: la cobertura de la MISMA póliza cuyo nombre corresponde a la causa. Solo
        -- las correspondencias que hoy son unívocas en los ramos configurados.
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

-- Verificación — los que quedan sin imputar son los que la regla va a saltear:
-- SELECT h.id, h.causa, c.nombre AS cobertura, h.estado_resolucion, h.monto_indemnizado
--   FROM aseguradora_bbva.siniestro_historico h
--   LEFT JOIN aseguradora_bbva.cobertura c ON c.id = h.cobertura_id
--  ORDER BY h.id;
