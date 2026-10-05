-- 2026-10-01 · The provider catalog stops being named after the expert. expert_firm serves loss
-- adjusters and repair shops alike (provider_type), so it becomes service_provider, and
-- expert_assessment, the referral to either of them, becomes case_referral:
--   · expert_firm.branch_id (one branch, or NULL for all) becomes service_provider_branch (N:M).
--     A provider with a branch keeps it as its only row; one with NULL gets no rows and stays a
--     generalist.
--   · expert_name / expert_email / expert_firm_id → provider_name / provider_email / provider_id.
--   · insured_fraud_record.expert_assessment_id → case_referral_id, since it points at that table.
-- Constraints and identity sequences follow the new table names, matching init-multitenant.sql.
-- Apply before deploying cases-, classification- and reports-service (ddl-auto=validate). Afterwards
-- run the CREATE OR REPLACE FUNCTION arbiter_common.create_tenant_schema block from
-- init-multitenant.sql, or a new insurer still gets expert_firm and expert_assessment. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
    con    RECORD;
    seq    TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        -- ─── expert_firm → service_provider ─────────────────────────────────────
        IF to_regclass(format('%I.expert_firm', tenant)) IS NOT NULL THEN
            EXECUTE format('ALTER TABLE %I.expert_firm RENAME TO service_provider', tenant);
        END IF;

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I.service_provider_branch (
                service_provider_id BIGINT NOT NULL REFERENCES %I.service_provider(id) ON DELETE CASCADE,
                branch_id           BIGINT NOT NULL REFERENCES arbiter_common.branch(id),
                PRIMARY KEY (service_provider_id, branch_id)
            )', tenant, tenant);

        IF EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = tenant AND table_name = 'service_provider'
                      AND column_name = 'branch_id') THEN
            EXECUTE format(
                'INSERT INTO %I.service_provider_branch (service_provider_id, branch_id)
                 SELECT id, branch_id FROM %I.service_provider WHERE branch_id IS NOT NULL
                 ON CONFLICT DO NOTHING', tenant, tenant);
            EXECUTE format('ALTER TABLE %I.service_provider DROP COLUMN branch_id', tenant);
        END IF;

        -- ─── expert_assessment → case_referral ──────────────────────────────────
        IF to_regclass(format('%I.expert_assessment', tenant)) IS NOT NULL THEN
            EXECUTE format('ALTER TABLE %I.expert_assessment RENAME TO case_referral', tenant);
        END IF;

        IF EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = tenant AND table_name = 'case_referral'
                      AND column_name = 'expert_name') THEN
            EXECUTE format('ALTER TABLE %I.case_referral RENAME COLUMN expert_name TO provider_name', tenant);
            EXECUTE format('ALTER TABLE %I.case_referral RENAME COLUMN expert_email TO provider_email', tenant);
            EXECUTE format('ALTER TABLE %I.case_referral RENAME COLUMN expert_firm_id TO provider_id', tenant);
        END IF;

        IF EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = tenant AND table_name = 'insured_fraud_record'
                      AND column_name = 'expert_assessment_id') THEN
            EXECUTE format(
                'ALTER TABLE %I.insured_fraud_record RENAME COLUMN expert_assessment_id TO case_referral_id',
                tenant);
        END IF;

        -- ─── names that still say expert ────────────────────────────────────────
        -- Covers the PKs, CHECKs, the UNIQUE and the auto-named FKs, including the one on
        -- insured_fraud_record that points at case_referral.
        FOR con IN
            SELECT c.conname, t.relname
              FROM pg_constraint c
              JOIN pg_class t     ON t.oid = c.conrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = tenant
               AND t.relname IN ('service_provider', 'case_referral', 'insured_fraud_record')
               AND (c.conname LIKE 'expert\_%' OR c.conname LIKE '%expert\_assessment\_id%')
        LOOP
            EXECUTE format('ALTER TABLE %I.%I RENAME CONSTRAINT %I TO %I', tenant, con.relname,
                           con.conname,
                           replace(replace(replace(replace(con.conname,
                               'expert_assessment_id', 'case_referral_id'),
                               'expert_firm_id', 'provider_id'),
                               'expert_assessment', 'case_referral'),
                               'expert_firm', 'service_provider'));
        END LOOP;

        FOR seq IN
            SELECT s.relname
              FROM pg_class s
              JOIN pg_namespace n ON n.oid = s.relnamespace
             WHERE n.nspname = tenant AND s.relkind = 'S'
               AND s.relname IN ('expert_firm_id_seq', 'expert_assessment_id_seq')
        LOOP
            EXECUTE format('ALTER SEQUENCE %I.%I RENAME TO %I', tenant, seq,
                           replace(replace(seq, 'expert_assessment', 'case_referral'),
                                   'expert_firm', 'service_provider'));
        END LOOP;

    END LOOP;
END $$;

COMMIT;
