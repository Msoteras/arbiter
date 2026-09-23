/**
 * `0.25` → `25%`, `0.143` → `14,3%`: decimal comma and NO space before the sign. Angular's `percent`
 * pipe follows the es-AR CLDR pattern ("25 %"), hence this helper and its `rate` pipe wrapper.
 */
const FORMATTERS = new Map<number, Intl.NumberFormat>();

/** @param value fraction in [0,1]; null means "nothing to divide", which is not zero. */
export function formatRate(
  value: number | null | undefined,
  fractionDigits = 0,
  fallback = '—',
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return fallback;
  }
  return formatter(fractionDigits).format(value);
}

function formatter(fractionDigits: number): Intl.NumberFormat {
  let cached = FORMATTERS.get(fractionDigits);
  if (!cached) {
    cached = new Intl.NumberFormat('es-AR', {
      style: 'percent',
      maximumFractionDigits: fractionDigits,
    });
    FORMATTERS.set(fractionDigits, cached);
  }
  return cached;
}
