-- 2026-09-28 · Each coverage says what its deductible percentage applies to: the sum insured, as
-- BBVA's phone policy states ("10% de la suma asegurada"), or the amount actually indemnified, as
-- most of the market does ("% del siniestro"). On the sum insured a cheap repair pays nothing.
-- The settlement freezes the basis it used, so a later change doesn't rewrite a signed sheet;
-- existing settlements were all calculated on the sum insured, which is the column default.
--
-- Initial configuration: BBVA's Robo de celular and Hurto keep the sum insured (their policy says
-- so); Daño accidental in both insurers and Provincia's Robo de celular and Hurto take it from
-- the loss. Idempotent. Apply before deploying cases-service (ddl-auto=validate).

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        EXECUTE format(
            'ALTER TABLE %I.coverage
                ADD COLUMN IF NOT EXISTS deductible_basis VARCHAR(20) NOT NULL DEFAULT ''SUM_INSURED''',
            tenant);
        EXECUTE format('ALTER TABLE %I.coverage DROP CONSTRAINT IF EXISTS coverage_deductible_basis_check',
                       tenant);
        EXECUTE format(
            'ALTER TABLE %I.coverage ADD CONSTRAINT coverage_deductible_basis_check
                CHECK (deductible_basis IN (''SUM_INSURED'', ''LOSS_AMOUNT''))',
            tenant);

        EXECUTE format(
            'ALTER TABLE %I.case_settlement
                ADD COLUMN IF NOT EXISTS deductible_basis VARCHAR(20) NOT NULL DEFAULT ''SUM_INSURED''',
            tenant);
        EXECUTE format(
            'ALTER TABLE %I.case_settlement DROP CONSTRAINT IF EXISTS case_settlement_deductible_basis_check',
            tenant);
        EXECUTE format(
            'ALTER TABLE %I.case_settlement ADD CONSTRAINT case_settlement_deductible_basis_check
                CHECK (deductible_basis IN (''SUM_INSURED'', ''LOSS_AMOUNT''))',
            tenant);

        EXECUTE format(
            'UPDATE %I.coverage SET deductible_basis = ''LOSS_AMOUNT'' WHERE name = ''Daño accidental''',
            tenant);
    END LOOP;
END $$;

UPDATE arbiter_provincia.coverage SET deductible_basis = 'LOSS_AMOUNT'
 WHERE name IN ('Robo de celular', 'Hurto');

COMMIT;

-- Check:
-- SELECT name, deductible, deductible_basis FROM arbiter_bbva.coverage ORDER BY id;
-- SELECT name, deductible, deductible_basis FROM arbiter_provincia.coverage ORDER BY id;
