-- 2026-11-21 · Tomás Ibarra files the final presentation's second case (accidental damage of a
-- laptop, the complex one) instead of being case 1's fallback. The case only works if it misses
-- Fast Track on the amount: 445.000 over a 600.000 damage sum (0.74) is past BBVA's 0.6 ratio.
-- His policy came with 1.500.000 (0.30), which fast-tracks it. Same sums as POL-TEC-2026-782.
-- Insurer database only. Run it before Tomás's first claim: Arbiter snapshots the policy then.
-- Idempotent.

BEGIN;

UPDATE aseguradora_bbva.cobertura c
   SET suma_asegurada = 600000.00
  FROM aseguradora_bbva.poliza p
 WHERE p.id = c.poliza_id
   AND p.numero = 'POL-TEC-2026-640'
   AND c.nombre = U&'Da\00F1o accidental'
   AND c.suma_asegurada <> 600000.00;

COMMIT;
