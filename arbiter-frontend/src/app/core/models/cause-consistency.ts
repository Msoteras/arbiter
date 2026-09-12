import { StatusTone } from './status-tone';

// Espejo del enum CauseConsistency de common-lib
// (ar.edu.utn.frba.arbiter.common.enums.CauseConsistency).
// cases-service lo devuelve como String en el campo `causeConsistency`, y null
// cuando el modelo no corrió (Fast Track, exclusión dura) o cuando la
// clasificación es anterior a este chequeo: ausente es "no evaluado", no MATCHES.
export type CauseConsistency = 'MATCHES' | 'AMBIGUOUS' | 'CONTRADICTS';

const LABELS: Record<CauseConsistency, string> = {
  MATCHES: 'El relato coincide',
  AMBIGUOUS: 'El relato no alcanza para confirmarlo',
  CONTRADICTS: 'El relato describe otro hecho',
};

export function causeConsistencyLabel(value: string): string {
  return (LABELS as Record<string, string>)[value] ?? value;
}

// Tono de semáforo. Coincide → ok; dudoso → warning; contradice → danger.
// Es una señal para el analista, no un veredicto: la decisión sigue siendo suya.
const TONES: Record<CauseConsistency, StatusTone> = {
  MATCHES: 'ok',
  AMBIGUOUS: 'warning',
  CONTRADICTS: 'danger',
};

export function causeConsistencyTone(value: string): StatusTone {
  return (TONES as Record<string, StatusTone>)[value] ?? 'neutral';
}

/**
 * Si amerita mostrarle el bloque al analista. Un MATCHES no dice nada que el
 * expediente no diga ya, y un null significa que el chequeo no corrió.
 */
export function shouldSurfaceCauseConsistency(value: string | null | undefined): boolean {
  return value === 'AMBIGUOUS' || value === 'CONTRADICTS';
}
