-- 2026-10-12 · Drops document_analysis.brand / model / imei, the follow-up that
-- 2026-10-11-atributos-del-documento.sql left pending: their values already live in
-- document_attribute and nothing deployed selects them any more.
-- Refuses to run while a value is still missing from document_attribute. Idempotent.
-- poliza.imei stays: init-multitenant.sql still creates it.

BEGIN;

DO $$
DECLARE
    tenant      TEXT;
    not_copied  BIGINT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = tenant AND table_name = 'document_analysis'
                   AND column_name = 'brand') THEN
            RAISE NOTICE '%: ya no tiene las columnas', tenant;
            CONTINUE;
        END IF;

        EXECUTE format($sql$
            SELECT count(*)
              FROM %1$I.document_analysis da
             CROSS JOIN LATERAL (VALUES
                    ('marca',  da.brand),
                    ('modelo', da.model),
                    ('IMEI',   da.imei)
                   ) AS a (name, value)
             WHERE btrim(coalesce(a.value, '')) <> ''
               AND NOT EXISTS (
                       SELECT 1 FROM %1$I.document_attribute existing
                        WHERE existing.analysis_id = da.id AND existing.name = a.name)
        $sql$, tenant) INTO not_copied;

        IF not_copied > 0 THEN
            RAISE EXCEPTION '%: % valores sin copiar a document_attribute; corré antes 2026-10-11-atributos-del-documento.sql',
                tenant, not_copied;
        END IF;

        EXECUTE format($sql$
            ALTER TABLE %1$I.document_analysis
                DROP COLUMN IF EXISTS brand,
                DROP COLUMN IF EXISTS model,
                DROP COLUMN IF EXISTS imei;
        $sql$, tenant);

        RAISE NOTICE '%: columnas borradas', tenant;
    END LOOP;
END $$;

COMMIT;
