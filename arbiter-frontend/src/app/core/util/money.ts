// No decimals on purpose: amounts are in the hundreds of thousands. Calculations use the number.
const ARS = new Intl.NumberFormat('es-AR', {
  style: 'currency',
  currency: 'ARS',
  maximumFractionDigits: 0,
});

export function formatMoney(amount: number | null | undefined, fallback = '—'): string {
  return amount === null || amount === undefined ? fallback : ARS.format(amount);
}

const THOUSANDS = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 0 });

/** `612500.5` → `612.500,5`, keeping a trailing comma while the cents are still being typed. */
export function amountInputLabel(value: string): string {
  if (value === '') {
    return '';
  }
  const [whole, cents] = value.split('.');
  const label = THOUSANDS.format(Number(whole));
  return cents === undefined ? label : `${label},${cents}`;
}

export function amountInputValue(typed: string): string {
  const [whole, ...cents] = typed.replace(/[^\d,]/g, '').split(',');
  const digits = whole.replace(/^0+(?=\d)/, '');
  return cents.length === 0 ? digits : `${digits || '0'}.${cents.join('').slice(0, 2)}`;
}

/** Shows both the absolute deductible and its contracted percentage when available. */
export function formatDeductible(
  amount: number | null | undefined,
  pct: number | null | undefined,
): string {
  if (amount === null || amount === undefined) {
    return pct === null || pct === undefined ? '—' : `${formatPct(pct)}`;
  }
  return pct === null || pct === undefined
    ? formatMoney(amount)
    : `${formatMoney(amount)} · ${formatPct(pct)}`;
}

/** `10.00` → `10%`, `12.50` → `12,5%`. No space before the sign, same as {@link formatRate}. */
function formatPct(pct: number): string {
  return `${new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 }).format(pct)}%`;
}
