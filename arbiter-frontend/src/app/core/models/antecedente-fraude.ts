import { StatusTone } from './status-tone';

/** Mirrors common-lib's FraudRecordSource enum. */
export type OrigenAntecedente = 'EXPERT_BACKED' | 'ANALYST_DECLARED';

const ORIGEN_LABELS: Record<OrigenAntecedente, string> = {
  EXPERT_BACKED: 'Con respaldo pericial',
  ANALYST_DECLARED: 'Declarado por el analista',
};

export function origenAntecedenteLabel(value: string): string {
  return (ORIGEN_LABELS as Record<string, string>)[value] ?? value;
}

/**
 * Mirrors FraudRecordResponse. `inForce`: within the insurer's window. `scores`: also weighs in the
 * engine, which requires an expert report. Keep them apart in the UI.
 */
export interface AntecedenteFraude {
  id: number;
  insuredDni: string;
  caseId: number;
  source: OrigenAntecedente;
  reason: string;
  expertAssessmentId: number | null;
  declaredByAnalystName: string;
  declaredAt: string;
  inForce: boolean;
  scores: boolean;
}

/**
 * - `pericial`: in force and expert-backed; the only one that scores and can veto Fast Track.
 * - `declarado`: in force without an expert report; shown, not counted.
 * - `vencido`: outside the window; still shown, since "there was one" differs from "none".
 */
export type EstadoAntecedente = 'pericial' | 'declarado' | 'vencido';

export function estadoAntecedente(a: AntecedenteFraude): EstadoAntecedente {
  if (!a.inForce) {
    return 'vencido';
  }
  return a.scores ? 'pericial' : 'declarado';
}

const ESTADO_LABELS: Record<EstadoAntecedente, string> = {
  pericial: 'Vigente · con respaldo pericial',
  declarado: 'Vigente · sin respaldo pericial',
  vencido: 'Fuera de la ventana de vigencia',
};

export function estadoAntecedenteLabel(a: AntecedenteFraude): string {
  return ESTADO_LABELS[estadoAntecedente(a)];
}

// Tone follows actual weight: `danger` only when the record feeds the engine.
const ESTADO_TONES: Record<EstadoAntecedente, StatusTone> = {
  pericial: 'danger',
  declarado: 'warning',
  vencido: 'neutral',
};

export function estadoAntecedenteTone(a: AntecedenteFraude): StatusTone {
  return ESTADO_TONES[estadoAntecedente(a)];
}

const ESTADO_EFECTOS: Record<EstadoAntecedente, string> = {
  pericial: 'Suma al nivel de riesgo y puede impedir la vía rápida.',
  declarado: 'No suma al nivel de riesgo: se muestra como alerta porque no tuvo peritaje detrás.',
  vencido: 'Ya no pesa: pasó la ventana de vigencia que configuró la aseguradora.',
};

export function efectoAntecedente(a: AntecedenteFraude): string {
  return ESTADO_EFECTOS[estadoAntecedente(a)];
}

export interface RegistrarAntecedenteRequest {
  source: OrigenAntecedente;
  reason: string;
}

/** Same minimum cases-service validates. */
export const MOTIVO_ANTECEDENTE_MIN = 20;
