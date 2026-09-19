/**
 * Comparar un indicador contra el período anterior, en un solo lugar.
 *
 * Vive en `core/` y no en una feature porque lo usan dos pantallas —el tablero del referente y el
 * reporte de resolución— y lo que tiene que ser igual en las dos no es el cálculo (restar es
 * fácil), sino el criterio: cuándo una comparación se muestra y cuándo callarse. Dos copias de eso
 * se desincronizan en cuanto alguien ajuste una y no la otra, y el síntoma sería un tablero y un
 * reporte diciendo cosas distintas del mismo mes.
 */

/**
 * Debajo de esta cantidad de casos en el período anterior no se muestra ninguna variación.
 *
 * Con una base chica el porcentaje es ruido con cara de resultado: pasar de 1 a 2 expedientes es
 * "100% más" y no significa nada. El número absoluto sigue en la tarjeta; lo que se omite es el
 * veredicto sobre su dirección.
 */
export const MIN_COMPARISON_BASE = 5;

/**
 * La variación de un indicador contra el período anterior, ya resuelta: cuánto cambió y si eso es
 * bueno o malo. Lo segundo no se deduce del signo — que suba el tiempo de resolución es peor, que
 * suba la tasa de aprobación no es ni bueno ni malo —, así que lo decide quien llama.
 */
export interface MetricDelta {
  /** Diferencia absoluta contra el período anterior. Null si no hay con qué comparar. */
  value: number | null;
  direction: 'up' | 'down' | 'flat';
}

export function delta(current: number | null, previous: number | null): MetricDelta {
  if (current === null || previous === null) {
    return { value: null, direction: 'flat' };
  }
  const difference = current - previous;
  const rounded = Math.abs(difference) < 0.0001 ? 0 : difference;
  return { value: rounded, direction: rounded === 0 ? 'flat' : rounded > 0 ? 'up' : 'down' };
}

/**
 * La variación con su flecha y su veredicto, a partir del tamaño ya formateado por quien llama
 * (cada pantalla sabe si lo suyo son horas, puntos porcentuales o expedientes).
 *
 * `good` dice qué dirección es la buena, porque del signo no se deduce. Sin eso, un ▲ al lado de
 * un número que mejoró sería exactamente el tipo de cartel que hace desconfiar del tablero entero.
 */
export function trendLabel(
  change: MetricDelta,
  amount: string,
  good: 'up' | 'down' | 'neither',
): string {
  if (change.value === null || change.direction === 'flat') {
    return '';
  }
  const arrow = change.direction === 'up' ? '▲' : '▼';
  // Que se resuelvan más o menos expedientes no es mejor ni peor: es el volumen del período. Poner
  // un veredicto ahí sería inventar una opinión que el dato no tiene.
  const tail =
    good === 'neither'
      ? 'vs. el período anterior'
      : `${change.direction === good ? 'mejor' : 'peor'} que el período anterior`;
  return `${arrow} ${amount} ${tail}`;
}

/**
 * La diferencia entre dos tasas, en PUNTOS porcentuales y no en porcentaje.
 *
 * Pasar de 33% a 50% es "+17 pp", no "+17%": lo segundo se lee como un aumento relativo (que sería
 * del 52%) y es una afirmación distinta y falsa. La distinción importa justo donde más se mira.
 */
export function percentagePoints(size: number): string {
  return `${Math.round(size * 100)} pp`;
}
