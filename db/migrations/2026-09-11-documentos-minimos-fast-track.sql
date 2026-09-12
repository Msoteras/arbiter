-- =============================================================================
-- La primera tanda de documentos: lo mínimo que se le pide al asegurado
--
-- `insurer_rule.configuration -> requiredDocumentTypes` pasa a ser la lista que se
-- le pide en el alta y lo único que el gate mira para resolver el carril rápido.
-- La agenda documental completa (document_requirement) se exige recién si el caso
-- NO fast-trackea, y para eso el asegurado ya tiene la pantalla de Documentación.
--
-- Por cobertura, porque es donde vive la configuración del carril rápido:
--
--   Robo de celular / Hurto   police_report + purchase_proof
--                             acreditan el hecho y la titularidad; la baja de IMEI
--                             y la última conexión llegan en la segunda ronda.
--   Daño accidental           purchase_proof + repair_quote
--                             el presupuesto es lo que fija el monto.
--
-- Sin item_photo a propósito: el criterio compara el TEXTO EXTRAÍDO de cada
-- documento, y una foto no tiene texto — pedirla acá dejaría sin Fast Track a
-- todos los casos de daño.
--
-- De paso corrige Provincia, Daño accidental de Tecnología Portátil, que tenía
-- cargado ["police_report"]: su agenda no pide denuncia policial (un daño
-- accidental no tiene denuncia), así que ningún caso de esa cobertura podía entrar
-- al carril rápido.
--
-- Idempotente: reescribe la clave con el mismo valor.
--
-- Uso:
--   psql "$DATABASE_URL" -f db/migrations/2026-09-11-documentos-minimos-fast-track.sql
-- =============================================================================

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

-- ─── Verificación ────────────────────────────────────────────────────────────
--
-- 1. La primera tanda de cada cobertura, por tenant:
--
-- SELECT c.name, r.active, r.configuration->'requiredDocumentTypes'
--   FROM arbiter_bbva.insurer_rule r JOIN arbiter_bbva.coverage c ON c.id = r.coverage_id
--  WHERE r.rule_type = 'FAST_TRACK' ORDER BY c.id;
--
-- 2. Que ninguna pida item_photo (no tiene texto que extraer) ni denuncia
--    policial en una cobertura de daño:
--
-- SELECT c.name, r.configuration->'requiredDocumentTypes'
--   FROM arbiter_provincia.insurer_rule r JOIN arbiter_provincia.coverage c ON c.id = r.coverage_id
--  WHERE r.rule_type = 'FAST_TRACK'
--    AND (r.configuration->'requiredDocumentTypes' @> '"item_photo"'
--     OR (c.name = 'Daño accidental' AND r.configuration->'requiredDocumentTypes' @> '"police_report"'));
