import { StatusTone } from './status-tone';

// Espejo del enum ExpertVerdict de common-lib
// (ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict).
export type ExpertVerdict = 'FRAUD_CONFIRMED' | 'FRAUD_DISCARDED' | 'INCONCLUSIVE';

const VERDICT_LABELS: Record<ExpertVerdict, string> = {
  FRAUD_CONFIRMED: 'Fraude confirmado',
  FRAUD_DISCARDED: 'Fraude descartado',
  INCONCLUSIVE: 'No concluyente',
};

export function veredictoLabel(value: string): string {
  return (VERDICT_LABELS as Record<string, string>)[value] ?? value;
}

// A diferencia de la recomendación del modelo, esto es evidencia de una persona que inspeccionó
// el caso. Aun así no resuelve nada: el color señala qué encontró el perito, no qué hay que hacer
// — la decisión sigue siendo del analista.
const VERDICT_TONES: Record<ExpertVerdict, StatusTone> = {
  FRAUD_CONFIRMED: 'danger',
  FRAUD_DISCARDED: 'ok',
  INCONCLUSIVE: 'warning',
};

export function veredictoTone(value: string): StatusTone {
  return (VERDICT_TONES as Record<string, StatusTone>)[value] ?? 'neutral';
}

/** Un perito del catálogo de la aseguradora — espejo de ExpertFirmResponse. */
export interface Perito {
  id: number;
  name: string;
  email: string;
  zone: string | null;
  /** null = generalista (cubre todos los ramos). */
  branchName: string | null;
}

/**
 * Espejo de DerivationOptionsResponse. `eligible` combina la regla de la aseguradora (monto
 * mínimo) con que haya peritos disponibles; los dos montos vienen para poder explicar el "no"
 * en vez de mostrar un botón apagado sin motivo.
 */
export interface OpcionesDerivacion {
  eligible: boolean;
  minClaimedAmount: number | null;
  claimedAmount: number | null;
  firms: Perito[];
}

/** Espejo de ExpertAssessmentResponse. Sin informe todavía, `verdict` y `reportReceivedAt` son null. */
export interface Peritaje {
  id: number;
  expertName: string;
  expertEmail: string;
  zone: string | null;
  reason: string;
  derivedAt: string;
  derivedByName: string;
  /** false = el mail nunca salió; el expediente estaría esperando a alguien a quien nadie avisó. */
  notified: boolean;
  reportReceivedAt: string | null;
  verdict: ExpertVerdict | null;
  verdictNote: string | null;
  reportDocumentId: number | null;
}
