/**
 * Absolute difference against the previous period. Whether the direction is good news depends on
 * the metric, so the caller decides that.
 */
export interface MetricDelta {
  /** Null when there is nothing to compare against. */
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

/** Minimum previous-period cases for a comparison to mean something; below it, one case is noise. */
export const DEFAULT_MIN_COMPARISON_BASE = 5;

export interface TrendParams {
  current: number | null;
  previous: number | null;
  /** Formats the absolute change: "4 d", "3,2%", "12". */
  format: (size: number) => string;
  /** Which direction is good news; 'neither' for pure volume metrics. */
  good: 'up' | 'down' | 'neither';
  /** Previous-period case count, checked against {@link DEFAULT_MIN_COMPARISON_BASE}. */
  base?: number;
  minBase?: number;
}

/** "▲ 4 d peor que el período anterior", or "" when there is no data, no change or too small a base. */
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
  const tail =
    good === 'neither'
      ? 'vs. el período anterior'
      : `${change.direction === good ? 'mejor' : 'peor'} que el período anterior`;
  return `${arrow} ${amount} ${tail}`;
}

/** Rate differences are percentage POINTS: 33% → 50% is "+17 pp", not a relative "+17%". */
export function percentagePoints(size: number): string {
  return `${Math.round(size * 100)} pp`;
}
