-- 2026-10-14 · Drops coverage.claim_exhausts_coverage. It said the same as an events cap of 1
-- (max_events_per_year), so its effect moves there before the column goes:
--   1. Every coverage with the flag on gets max_events_per_year = 1.
--   2. If that coverage has no active MAX_EVENTS_YEAR rule, one is created (or reactivated): the
--      engine only checks the cap when the rule is on.
-- Old rule_result rows with CLAIM_EXHAUSTS_COVERAGE stay as history; the frontend still labels them.
-- Idempotent. Apply after deploying the code: the entity no longer maps the column, and its
-- DEFAULT FALSE covers the inserts made in between.

BEGIN;

DO $$
DECLARE
    tenant  TEXT;
    cov     RECORD;
    author   BIGINT;
    has_rule    BOOLEAN;
    reactivated INTEGER;
BEGIN
    FOR tenant IN SELECT schema_name FROM arbiter_common.insurer LOOP
        IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = tenant AND table_name = 'coverage'
                   AND column_name = 'claim_exhausts_coverage') THEN
            RAISE NOTICE '%: ya no tiene la columna', tenant;
            CONTINUE;
        END IF;

        FOR cov IN EXECUTE format(
                'SELECT id, name, branch_id, max_events_per_year FROM %I.coverage
                  WHERE claim_exhausts_coverage', tenant) LOOP

            IF cov.max_events_per_year IS DISTINCT FROM 1 THEN
                EXECUTE format('UPDATE %I.coverage SET max_events_per_year = 1 WHERE id = $1', tenant)
                    USING cov.id;
                RAISE NOTICE '%: cobertura % (%) pasa a tope 1 (tenía %)',
                    tenant, cov.id, cov.name, coalesce(cov.max_events_per_year::TEXT, 'sin tope');
            END IF;

            EXECUTE format(
                'UPDATE %I.insurer_rule SET active = TRUE
                  WHERE coverage_id = $1 AND rule_type = ''MAX_EVENTS_YEAR'' AND NOT active', tenant)
                USING cov.id;
            GET DIAGNOSTICS reactivated = ROW_COUNT;
            IF reactivated > 0 THEN
                RAISE NOTICE '%: cobertura % (%) reactiva la regla de tope de eventos', tenant, cov.id, cov.name;
            END IF;

            -- EXECUTE does not set FOUND, hence the EXISTS.
            EXECUTE format(
                'SELECT EXISTS (SELECT 1 FROM %I.insurer_rule
                                 WHERE coverage_id = $1 AND rule_type = ''MAX_EVENTS_YEAR'')', tenant)
                INTO has_rule USING cov.id;
            IF NOT has_rule THEN
                -- Whoever set up the coverage's other rules; the oldest rule of the insurer otherwise.
                EXECUTE format(
                    'SELECT created_by FROM %I.insurer_rule
                      ORDER BY (coverage_id = $1) DESC NULLS LAST, id LIMIT 1', tenant)
                    INTO author USING cov.id;
                EXECUTE format(
                    'INSERT INTO %I.insurer_rule (active, valid_from, name, rule_type, effect, priority,
                                                  blocks_fast_track, branch_id, coverage_id, configuration,
                                                  created_by)
                     VALUES (TRUE, now(), $1, ''MAX_EVENTS_YEAR'', ''DERIVAR'', 5, TRUE, $2, $3, ''{}'', $4)',
                    tenant)
                    USING 'Tope de eventos por año (cobertura ' || cov.id || ')', cov.branch_id, cov.id, author;
                RAISE NOTICE '%: cobertura % (%) suma la regla de tope de eventos', tenant, cov.id, cov.name;
            END IF;
        END LOOP;

        EXECUTE format('ALTER TABLE %I.coverage DROP COLUMN IF EXISTS claim_exhausts_coverage', tenant);
        RAISE NOTICE '%: columna borrada', tenant;
    END LOOP;
END $$;

COMMIT;
