import { StatusTone } from './status-tone';

// Mirrors common-lib's CauseConsistency. Null means "not evaluated", never MATCHES.
export type CauseConsistency = 'MATCHES' | 'AMBIGUOUS' | 'CONTRADICTS';

const LABELS: Record<CauseConsistency, string> = {
  MATCHES: 'El relato coincide',
  AMBIGUOUS: 'El relato no alcanza para confirmarlo',
  CONTRADICTS: 'El relato describe otro hecho',
};

export function causeConsistencyLabel(value: string): string {
  return (LABELS as Record<string, string>)[value] ?? value;
}

const TONES: Record<CauseConsistency, StatusTone> = {
  MATCHES: 'ok',
  AMBIGUOUS: 'warning',
  CONTRADICTS: 'danger',
};

export function causeConsistencyTone(value: string): StatusTone {
  return (TONES as Record<string, StatusTone>)[value] ?? 'neutral';
}

/** MATCHES adds nothing the case does not already say; null means the check did not run. */
export function shouldSurfaceCauseConsistency(value: string | null | undefined): boolean {
  return value === 'AMBIGUOUS' || value === 'CONTRADICTS';
}
