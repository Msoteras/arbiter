-- 2026-09-11 · FAST_TRACK requiredDocumentTypes becomes the first batch asked at filing and all the gate
-- checks: Robo/Hurto police_report + purchase_proof; Daño accidental purchase_proof + repair_quote. No
-- item_photo: the gate compares extracted text. Also fixes Provincia's Daño accidental, which asked for a
-- police report. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant  TEXT;
    minimos JSONB;
    nombre  TEXT;
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
             WHERE table_schema = tenant AND table_name = 'insurer_rule'
        ) THEN
            RAISE NOTICE 'Skipping %: no insurer_rule table', tenant;
            CONTINUE;
        END IF;

        FOREACH nombre IN ARRAY ARRAY['Robo de celular', 'Hurto', 'Daño accidental']
        LOOP
            minimos := CASE nombre
                WHEN 'Daño accidental' THEN '["purchase_proof","repair_quote"]'::jsonb
                ELSE '["police_report","purchase_proof"]'::jsonb
            END;

            EXECUTE format($dml$
                UPDATE %I.insurer_rule r
                   SET configuration = jsonb_set(
                           coalesce(r.configuration, '{}'::jsonb),
                           '{requiredDocumentTypes}', $1, TRUE)
                  FROM %I.coverage c
                 WHERE c.id = r.coverage_id
                   AND r.rule_type = 'FAST_TRACK'
                   AND c.name = $2
            $dml$, tenant, tenant) USING minimos, nombre;
        END LOOP;

        RAISE NOTICE 'Migrated %', tenant;
    END LOOP;
END $$;

COMMIT;

-- ─── Verification ────────────────────────────────────────────────────────────
--
-- 1. Each coverage's first batch, per tenant:
--
-- SELECT c.name, r.active, r.configuration->'requiredDocumentTypes'
--   FROM arbiter_bbva.insurer_rule r JOIN arbiter_bbva.coverage c ON c.id = r.coverage_id
--  WHERE r.rule_type = 'FAST_TRACK' ORDER BY c.id;
--
-- 2. None asks for item_photo, nor for a police report on a damage coverage:
--
-- SELECT c.name, r.configuration->'requiredDocumentTypes'
--   FROM arbiter_provincia.insurer_rule r JOIN arbiter_provincia.coverage c ON c.id = r.coverage_id
--  WHERE r.rule_type = 'FAST_TRACK'
--    AND (r.configuration->'requiredDocumentTypes' @> '"item_photo"'
--     OR (c.name = 'Daño accidental' AND r.configuration->'requiredDocumentTypes' @> '"police_report"'));
