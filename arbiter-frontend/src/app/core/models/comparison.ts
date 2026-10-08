import { HttpParams } from '@angular/common/http';

// Mirrors reports-service's ComparisonMode and ReportComparison.

export type ComparisonMode = 'PREVIOUS_PERIOD' | 'SAME_PERIOD_LAST_YEAR' | 'CUSTOM';

export interface ReportComparison {
  mode: ComparisonMode;
  from: string;
  to: string;
}

/** What the user picked. `from`/`to` only travel with `CUSTOM`: the backend works the rest out. */
export interface ComparisonChoice {
  mode: ComparisonMode;
  from: string;
  to: string;
}

export const COMPARISON_MODES: ComparisonMode[] = [
  'PREVIOUS_PERIOD',
  'SAME_PERIOD_LAST_YEAR',
  'CUSTOM',
];

const LABELS: Record<ComparisonMode, string> = {
  PREVIOUS_PERIOD: 'Período anterior',
  SAME_PERIOD_LAST_YEAR: 'Mismo período, un año antes',
  CUSTOM: 'Otro período',
};

/** How a trend names what it compares against: "▲ 4 d peor que un año antes". */
const REFERENCES: Record<ComparisonMode, string> = {
  PREVIOUS_PERIOD: 'el período anterior',
  SAME_PERIOD_LAST_YEAR: 'un año antes',
  CUSTOM: 'el período comparado',
};

export function comparisonLabel(mode: ComparisonMode): string {
  return LABELS[mode];
}

export function comparisonReference(mode: ComparisonMode | undefined): string {
  return REFERENCES[mode ?? 'PREVIOUS_PERIOD'];
}

/** Anything a hand-typed link carries that isn't a mode means the default one. */
export function parseComparisonMode(value: string | null): ComparisonMode {
  return COMPARISON_MODES.includes(value as ComparisonMode)
    ? (value as ComparisonMode)
    : 'PREVIOUS_PERIOD';
}

/** The default mode is left out: it is what the backend does without the parameter. */
export function withComparison(params: HttpParams, choice: ComparisonChoice): HttpParams {
  if (choice.mode === 'PREVIOUS_PERIOD') {
    return params;
  }
  const withMode = params.set('compare', choice.mode);
  return choice.mode === 'CUSTOM'
    ? withMode.set('compareFrom', choice.from).set('compareTo', choice.to)
    : withMode;
}
