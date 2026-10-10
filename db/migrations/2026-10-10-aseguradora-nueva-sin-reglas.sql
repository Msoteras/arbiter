-- 2026-10-10 · A new insurer starts with the platform catalog (branches and claim causes) and nothing
-- else: no coverages, rules, document schedule, scoring or settlement caps. Its referent configures
-- them. The demo configuration BBVA and Provincia started from moved to db/seed-demo.sql.
--   · create_tenant_schema loses p_created_by, which only stamped the author of those default rules.
--     CREATE OR REPLACE with one parameter would add an overload and leave the old function in
--     place, so it is dropped here: a call with two arguments must fail, not seed BBVA's rules.
--   · Existing insurers keep their rows. Nothing here touches tenant data.
-- Afterwards run the CREATE OR REPLACE FUNCTION arbiter_common.create_tenant_schema block from
-- init-multitenant.sql, or onboarding a new insurer fails because the function no longer exists.
-- Idempotent.

BEGIN;

DROP FUNCTION IF EXISTS arbiter_common.create_tenant_schema(TEXT, BIGINT);

COMMIT;
