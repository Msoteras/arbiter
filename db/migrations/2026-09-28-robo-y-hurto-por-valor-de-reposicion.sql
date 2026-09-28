-- 2026-09-28 · Robo de celular and Hurto settle by the lesser of the sum insured and the replacement
-- value. The phone policy carries annex 340, whose art. 7 (Bases de Indemnización) caps the payout at
-- the lower of the item's sum insured and the cost of replacing it with an identical one; art. 61 and
-- 65 of Ley 17.418 say the same. Until now both coverages paid the full sum insured whatever the
-- phone was worth.
-- Only rows still on the old default are touched, so a referent's own choice is kept. Idempotent.

BEGIN;

DO $$
DECLARE
    tenant TEXT;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        EXECUTE format(
            'UPDATE %I.coverage
                SET settlement_basis = ''LESSER_OF_SUM_AND_REPLACEMENT''
              WHERE name IN (''Robo de celular'', ''Hurto'')
                AND settlement_formula = ''TOTAL_LOSS''
                AND settlement_basis = ''SUM_INSURED''',
            tenant);
    END LOOP;
END $$;

COMMIT;

-- Check:
-- SELECT name, settlement_formula, settlement_basis FROM arbiter_bbva.coverage ORDER BY id;
-- SELECT name, settlement_formula, settlement_basis FROM arbiter_provincia.coverage ORDER BY id;
