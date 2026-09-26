-- 2026-08-25 · Martina's contact email (insured.email) in both tenants, where status notices go.
-- Not her login (users.email, checked against Auth0) nor the insurer's record. Idempotent.

BEGIN;

UPDATE arbiter_bbva.insured
   SET email = 'aylusandu@gmail.com'
 WHERE dni = '42.987.654';

UPDATE arbiter_provincia.insured
   SET email = 'aylusandu@gmail.com'
 WHERE dni = '42.987.654';

COMMIT;

-- Check.
SELECT 'bbva' AS tenant, dni, name, surname, email FROM arbiter_bbva.insured WHERE dni = '42.987.654'
UNION ALL
SELECT 'provincia', dni, name, surname, email FROM arbiter_provincia.insured WHERE dni = '42.987.654';
