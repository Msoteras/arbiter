-- =============================================================================
-- 2026-08-30 · rule_result.result: un solo vocabulario
--
-- Migración de DATOS, idempotente y acotada. Aplicar sobre una base que ya tiene
-- datos (Railway) sin pasar por el trío reset → init → seed.
--
-- El motor escribe PASS/FAIL (RuleFinding.result()). Un seed viejo escribía
-- CUMPLE/NO_CUMPLE, y esas filas siguen en pie: al 30/08 conviven los cuatro
-- literales en Railway.
--
--     arbiter_bbva       PASS 40 · FAIL 4 · CUMPLE 6 · NO_CUMPLE 3
--     arbiter_provincia  PASS  5 · FAIL 2 · CUMPLE 2 · NO_CUMPLE 2
--
-- El frontend las venía tapando con un alias (PASSED = ['PASS','CUMPLE']). Eso
-- no es defensa contra un caso hipotético: sostiene 13 filas que existen. Pero
-- deja el vocabulario partido para siempre, y cada lector nuevo del código tiene
-- que enterarse de que hay dos. Se unifica acá, en los datos, y el alias se va.
--
-- `seed-demo.sql` ya escribe PASS/FAIL, así que una base recreada desde cero
-- nunca vuelve a tener los literales viejos: esto aplica sólo a las que ya
-- existían.
--
-- ORDEN: aplicar ANTES de desplegar el frontend que saca el alias. Si el código
-- sube primero, esas 13 filas se muestran con el literal crudo y sin tono —
-- degradado prolijo, no roto, pero feo.
--
-- Idempotente: correrlo dos veces no cambia nada la segunda.
-- =============================================================================

BEGIN;

DO $$
DECLARE
    tenant    TEXT;
    migradas  INTEGER;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        EXECUTE format(
            $sql$ UPDATE %I.rule_result
                     SET result = CASE result
                                      WHEN 'CUMPLE'    THEN 'PASS'
                                      WHEN 'NO_CUMPLE' THEN 'FAIL'
                                  END
                   WHERE result IN ('CUMPLE', 'NO_CUMPLE') $sql$, tenant);

        GET DIAGNOSTICS migradas = ROW_COUNT;
        RAISE NOTICE '%: % fila(s) migrada(s) a PASS/FAIL', tenant, migradas;

    END LOOP;
END $$;

COMMIT;

-- Verificación: sólo PASS y FAIL deberían quedar. Cualquier otro literal acá es
-- un vocabulario que este script no conocía — revisarlo antes de darlo por bueno.
SELECT table_schema, result, count(*) AS filas
  FROM (SELECT 'arbiter_bbva' AS table_schema, result FROM arbiter_bbva.rule_result
        UNION ALL
        SELECT 'arbiter_provincia', result FROM arbiter_provincia.rule_result) t
 GROUP BY table_schema, result
 ORDER BY table_schema, result;
