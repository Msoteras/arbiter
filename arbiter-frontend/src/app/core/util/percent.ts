/**
 * Porcentajes de la app: `0.25` → `25%`, `0.143` → `14,3%`.
 *
 * Con coma decimal y SIN espacio antes del signo. El espacio es decisión del equipo, no descuido:
 * el locale es-AR de Angular escribe "25 %" (así lo dice el CLDR), pero en pantalla queda raro y
 * el resto de la app —importes, franquicias— viene escribiendo "10%" desde siempre.
 *
 * Por eso esto existe en vez del pipe `percent` de Angular: el pipe toma el patrón del locale y no
 * se puede cambiar sin tocar los datos del locale. Todo lo que muestre un porcentaje pasa por acá
 * o por el pipe `rate`, que es su envoltorio para templates.
 */
const FORMATTERS = new Map<number, Intl.NumberFormat>();

/**
 * @param value            fracción entre 0 y 1, como la mandan los DTOs; null cuando no hay nada
 *                         que dividir, que no es lo mismo que cero
 * @param fractionDigits   decimales, sólo cuando el número los necesita para decir algo: las tasas
 *                         de fraude son chicas y "3,6%" y "4,4%" leídos como "4%" son el mismo dato
 * @param fallback         qué mostrar cuando el valor es desconocido
 */
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
