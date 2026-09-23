-- =============================================================================
-- 2026-09-22 · El hecho generador que narra cada documento
--
-- Agrega document_analysis.described_claim_cause: el hecho que el documento narra, leído
-- por la extracción (prompt extraccion-documento-v6.md) como un nombre del catálogo del
-- ramo, o NULL si el documento no narra ninguno (una factura, una constancia técnica).
--
-- Para qué: en Fast Track nadie comparaba el relato con el hecho declarado. Esa comparación
-- la hacía el LLM de clasificación, que en el carril rápido no corre, así que un hurto
-- declarado como robo entraba por Fast Track a una cobertura que excluye el hurto, con un
-- acta caratulada HURTO adjunta. Ahora la extracción del acta (que ya corre en el gate)
-- dice qué hecho narra y ClaimCauseConsistencyEvaluator lo compara por código. Avisa, no
-- bloquea (decisión del 22/09/2026): deja una fila CLAIM_CAUSE_MATCH en rule_result y un
-- motivo para el analista. rule_result.rule_type es VARCHAR(40) sin CHECK, así que el
-- literal nuevo no necesita migración propia.
--
-- Nullable y sin default: las extracciones anteriores no preguntaban por el hecho, y NULL
-- dice justamente eso — "el documento no lo dijo" —, que no es lo mismo que coincidir.
--
-- Idempotente: se puede correr más de una vez.
--
-- Uso:
--   psql "$DATABASE_URL" -f db/migrations/2026-09-22-hecho-descripto-en-documento.sql
-- =============================================================================

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN
        SELECT schema_name
          FROM information_schema.schemata
         WHERE schema_name LIKE 'arbiter\_%'
           AND schema_name <> 'arbiter_common'
         ORDER BY schema_name
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.tables
             WHERE table_schema = tenant AND table_name = 'document_analysis'
        ) THEN
            RAISE NOTICE 'Skipping %: no document_analysis table', tenant;
            CONTINUE;
        END IF;

        EXECUTE format(
            'ALTER TABLE %I.document_analysis ADD COLUMN IF NOT EXISTS described_claim_cause VARCHAR(120)',
            tenant);
        RAISE NOTICE '%: document_analysis.described_claim_cause OK', tenant;
    END LOOP;
END $$;

COMMIT;
