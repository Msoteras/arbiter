-- 2026-10-10 · Coverage names stop being the key to the insurer database, and stop repeating the
-- names of the claim causes they cover.
--   · coverage gains external_name, what the insurer database calls the coverage (cobertura.nombre).
--     It starts equal to name; from here on a rename only touches name, so incoming policies still
--     match. Same idea as branch.external_name (2026-10-05).
--   · name and external_name become unique per branch instead of per insurer: that database reuses
--     one name across lines ("Robo de celular" also covers a laptop).
--   · "Robo de celular" is renamed to "Robo". Its Fast Track rule follows.
--   · Tecnología Portátil's only damage cause, "Daño accidental", had the same name as the coverage
--     answering for it. It becomes "Rotura accidental" (same id, so cases already filed keep
--     pointing at it) and a new "Caída" is added, mirroring Celulares. The new cause copies the
--     document schedule of the old one, joins it in the branch's REPAIR_DERIVATION rule, and is
--     excluded by every coverage that already excluded the old one.
-- A rule whose configuration changes keeps its previous version in insurer_rule_history, attributed
-- to this migration (changed_by NULL). Text literals use U&'...' escapes because a PowerShell pipe
-- once turned an accent into '?'.
-- The deployed code neither reads nor writes external_name: apply this WHILE deploying, never
-- after. Coverage lives in common-lib, so every module that scans it fails ddl-auto: validate on
-- the missing column, not only cases- and classification-service: rules- and auth-service too. Afterwards
-- run the CREATE OR REPLACE FUNCTION arbiter_common.create_tenant_schema block from
-- init-multitenant.sql. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant          TEXT;
    now_ts          TIMESTAMPTZ := NOW();
    laptop_branch   BIGINT;
    breakage_id     BIGINT;
    drop_id         BIGINT;
    drop_created    BOOLEAN;
    rule            RECORD;
    list_key        TEXT;
    new_config      JSONB;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP

        -- ─── coverage.external_name ──────────────────────────────────────────────
        EXECUTE format('ALTER TABLE %I.coverage ADD COLUMN IF NOT EXISTS external_name VARCHAR(160)', tenant);
        EXECUTE format('UPDATE %I.coverage SET external_name = name WHERE external_name IS NULL', tenant);

        EXECUTE format('ALTER TABLE %I.coverage DROP CONSTRAINT IF EXISTS coverage_name_key', tenant);
        IF NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                        WHERE n.nspname = tenant AND c.conname = 'coverage_branch_name_unique') THEN
            EXECUTE format('ALTER TABLE %I.coverage ADD CONSTRAINT coverage_branch_name_unique '
                           'UNIQUE (branch_id, name)', tenant);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                        WHERE n.nspname = tenant AND c.conname = 'coverage_branch_external_name_unique') THEN
            EXECUTE format('ALTER TABLE %I.coverage ADD CONSTRAINT coverage_branch_external_name_unique '
                           'UNIQUE (branch_id, external_name)', tenant);
        END IF;

        -- ─── Robo de celular → Robo ──────────────────────────────────────────────
        EXECUTE format(
            'UPDATE %I.coverage SET name = ''Robo''
              WHERE name = ''Robo de celular'' AND external_name = ''Robo de celular''', tenant);
        EXECUTE format(
            'UPDATE %I.insurer_rule SET name = U&''Fast Track \2014 Robo''
              WHERE name = U&''Fast Track \2014 Robo de celular''', tenant);

        -- ─── Tecnología Portátil: Daño accidental → Rotura accidental + Caída ────
        EXECUTE format(
            'SELECT id FROM %I.branch WHERE external_name = U&''Tecnolog\00EDa Port\00E1til''', tenant)
            INTO laptop_branch;
        CONTINUE WHEN laptop_branch IS NULL;

        EXECUTE format(
            'UPDATE %I.claim_cause SET name = ''Rotura accidental''
              WHERE branch_id = $1 AND name = U&''Da\00F1o accidental''
                AND NOT EXISTS (SELECT 1 FROM %I.claim_cause
                                 WHERE branch_id = $1 AND name = ''Rotura accidental'')', tenant, tenant)
            USING laptop_branch;

        EXECUTE format(
            'SELECT id FROM %I.claim_cause WHERE branch_id = $1 AND name = ''Rotura accidental''', tenant)
            INTO breakage_id USING laptop_branch;
        CONTINUE WHEN breakage_id IS NULL;

        EXECUTE format(
            'SELECT id FROM %I.claim_cause WHERE branch_id = $1 AND name = U&''Ca\00EDda''', tenant)
            INTO drop_id USING laptop_branch;
        drop_created := drop_id IS NULL;
        IF drop_created THEN
            EXECUTE format(
                'INSERT INTO %I.claim_cause (name, branch_id) VALUES (U&''Ca\00EDda'', $1) RETURNING id', tenant)
                INTO drop_id USING laptop_branch;
        END IF;

        -- Only alongside the new cause: on a re-run the lists are the referent's again.
        CONTINUE WHEN NOT drop_created;

        EXECUTE format(
            'INSERT INTO %1$I.document_requirement (document_type, mandatory, risk_band, branch_id, claim_cause_id)
             SELECT document_type, mandatory, risk_band, branch_id, $2
               FROM %1$I.document_requirement WHERE claim_cause_id = $1', tenant)
            USING breakage_id, drop_id;

        FOR rule IN EXECUTE format(
                'SELECT id, rule_type, active, blocks_fast_track, configuration, valid_from
                   FROM %I.insurer_rule
                  WHERE branch_id = $1 AND rule_type IN (''COVERAGE_EXCLUSION'', ''REPAIR_DERIVATION'')', tenant)
                USING laptop_branch LOOP

            list_key := CASE rule.rule_type WHEN 'COVERAGE_EXCLUSION' THEN 'excludedClaimCauseIds'
                                            ELSE 'claimCauseIds' END;
            CONTINUE WHEN NOT COALESCE(rule.configuration -> list_key, '[]'::jsonb) @> to_jsonb(breakage_id);

            new_config := jsonb_set(rule.configuration, ARRAY[list_key],
                                    (rule.configuration -> list_key) || to_jsonb(drop_id));

            EXECUTE format(
                'INSERT INTO %I.insurer_rule_history (config_version, changed_at, reason, valid_from,
                                                      valid_to, rule_id, changed_by)
                 VALUES ($1, $2, $3, $4, $2, $5, NULL)', tenant)
                USING jsonb_build_object('active', rule.active,
                                         'blocksFastTrack', rule.blocks_fast_track,
                                         'configuration', rule.configuration,
                                         'legacy', false),
                      now_ts,
                      U&'Hecho generador \00ABCa\00EDda\00BB agregado por la migraci\00F3n del 10/10/2026',
                      rule.valid_from, rule.id;
            EXECUTE format(
                'UPDATE %I.insurer_rule SET configuration = $1, valid_from = $2 WHERE id = $3', tenant)
                USING new_config, now_ts, rule.id;
        END LOOP;

        EXECUTE format(
            'UPDATE %I.insurer_rule SET name = U&''Derivar a reparaci\00F3n: rotura accidental y ca\00EDda''
              WHERE branch_id = $1 AND name = U&''Derivar a reparaci\00F3n: da\00F1o accidental''', tenant)
            USING laptop_branch;
        EXECUTE format(
            'UPDATE %I.insurer_rule SET name = U&''La cobertura de da\00F1o accidental solo cubre rotura y ca\00EDda''
              WHERE branch_id = $1
                AND name = U&''La cobertura de da\00F1o accidental solo cubre da\00F1o accidental''', tenant)
            USING laptop_branch;

    END LOOP;
END $$;

COMMIT;

-- Check: coverages with both names, and the claim causes of each branch.
-- SELECT branch_id, name, external_name FROM arbiter_bbva.coverage ORDER BY branch_id, id;
-- SELECT branch_id, id, name FROM arbiter_provincia.claim_cause ORDER BY branch_id, id;
-- SELECT rule_type, name, configuration FROM arbiter_provincia.insurer_rule
--  WHERE rule_type IN ('COVERAGE_EXCLUSION', 'REPAIR_DERIVATION') ORDER BY branch_id, id;
