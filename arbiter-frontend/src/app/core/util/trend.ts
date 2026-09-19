/**
 * La variación de un indicador contra el período anterior, y el texto con flecha que la muestra en
 * una tarjeta de KPI. Un solo lugar para esto: lo usaban el dashboard operativo y ahora también los
 * dos reportes, y las tres pantallas tienen que leer "▲ 4 d peor que el período anterior" con el
 * mismo criterio, no con tres implementaciones que un día se desalinean.
 */

/**
 * Diferencia absoluta contra el período anterior, ya resuelta en dirección. Lo que NO resuelve es
 * si esa dirección es buena o mala noticia — que suba el tiempo de resolución es peor, que suba la
 * tasa de Fast Track no lo es —, así que eso lo decide quien llama.
 */
export interface MetricDelta {
  /** Null si no hay con qué comparar. */
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
 * Cuántos casos necesita el período anterior para que compararse contra él signifique algo. Por
 * debajo, un solo caso mueve una tasa varios puntos y la flecha anuncia un derrumbe que es apenas
 * ruido — el tipo de número que termina citado como un hecho.
 */
export const DEFAULT_MIN_COMPARISON_BASE = 5;

export interface TrendParams {
  current: number | null;
  previous: number | null;
  /** Da formato a la magnitud del cambio, ya en valor absoluto: "4 d", "3,2%", "12". */
  format: (size: number) => string;
  /**
   * Qué dirección es la buena noticia. 'neither' para lo que es puro volumen (cuántas denuncias
   * entraron) y no admite un veredicto de mejor/peor.
   */
  good: 'up' | 'down' | 'neither';
  /** Cuántos casos tuvo el período anterior, para la regla de {@link DEFAULT_MIN_COMPARISON_BASE}. */
  base?: number;
  minBase?: number;
}

/**
 * "▲ 4 d peor que el período anterior", o "" cuando no hay nada que decir: sin datos para
 * comparar, el cambio es cero, o el período anterior no tiene base suficiente.
 */
export function trendText(params: TrendParams): string {
  const {
    current,
    previous,
    format,
    good,
    base = Number.POSITIVE_INFINITY,
    minBase = DEFAULT_MIN_COMPARISON_BASE,
  } = params;
  if (base < minBase) {
    return '';
  }
  const change = delta(current, previous);
  if (change.value === null || change.direction === 'flat') {
    return '';
  }
  const arrow = change.direction === 'up' ? '▲' : '▼';
  const amount = format(Math.abs(change.value));
  // Que entren más o menos siniestros no es mejor ni peor: es el volumen del período. Poner un
  // veredicto ahí sería inventar una opinión que el dato no tiene.
  const tail =
    good === 'neither'
      ? 'vs. el período anterior'
      : `${change.direction === good ? 'mejor' : 'peor'} que el período anterior`;
  return `${arrow} ${amount} ${tail}`;
}
