/**
 * Formateo de importes en pesos, centralizado.
 *
 * Sin decimales a propósito: las sumas aseguradas y las franquicias del negocio son de cientos de
 * miles, y ahí los centavos son ruido que además desalinea la columna. Lo que decide plata (el
 * cálculo del monto a pagar) trabaja con el número, no con este string.
 */
const ARS = new Intl.NumberFormat('es-AR', {
  style: 'currency',
  currency: 'ARS',
  maximumFractionDigits: 0,
});

export function formatMoney(amount: number | null | undefined, fallback = '—'): string {
  return amount === null || amount === undefined ? fallback : ARS.format(amount);
}

/**
 * Franquicia tal como la pregunta el asegurado y tal como se la cobran: el porcentaje es el dato
 * que le dieron al contratar, el absoluto es lo que le van a descontar. Mostrar uno solo deja
 * siempre la otra mitad de la pregunta sin responder.
 */
export function formatDeductible(amount: number | null | undefined, pct: number | null | undefined): string {
  if (amount === null || amount === undefined) {
    return pct === null || pct === undefined ? '—' : `${formatPct(pct)}`;
  }
  return pct === null || pct === undefined
    ? formatMoney(amount)
    : `${formatMoney(amount)} · ${formatPct(pct)}`;
}

/** `10.00` → `10%`, `12.50` → `12,5%`. */
function formatPct(pct: number): string {
  return `${new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 }).format(pct)}%`;
}
