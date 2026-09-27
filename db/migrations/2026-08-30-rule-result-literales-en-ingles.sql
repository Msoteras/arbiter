-- 2026-08-30 · Data: an old seed wrote rule_result CUMPLE/NO_CUMPLE; everything becomes PASS/FAIL.
-- Apply before deploying the frontend that drops the alias, or those rows show without a tone. Idempotent.

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

-- Check: only PASS and FAIL should remain; any other literal needs a look first.
SELECT table_schema, result, count(*) AS filas
  FROM (SELECT 'arbiter_bbva' AS table_schema, result FROM arbiter_bbva.rule_result
        UNION ALL
        SELECT 'arbiter_provincia', result FROM arbiter_provincia.rule_result) t
 GROUP BY table_schema, result
 ORDER BY table_schema, result;
