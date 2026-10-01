import { StatusTone } from './status-tone';

/** Mirrors common-lib's FraudRecordSource enum. */
export type FraudRecordOrigin = 'EXPERT_BACKED' | 'ANALYST_DECLARED';

const ORIGEN_LABELS: Record<FraudRecordOrigin, string> = {
  EXPERT_BACKED: 'Con respaldo pericial',
  ANALYST_DECLARED: 'Declarado por el analista',
};

export function fraudRecordOriginLabel(value: string): string {
  return (ORIGEN_LABELS as Record<string, string>)[value] ?? value;
}

/**
 * Mirrors FraudRecordResponse. `inForce`: within the insurer's window. `scores`: also weighs in the
 * engine, which requires an expert report. Keep them apart in the UI.
 */
export interface FraudRecord {
  id: number;
  insuredDni: string;
  caseId: number;
  source: FraudRecordOrigin;
  reason: string;
  caseReferralId: number | null;
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
export type FraudRecordStatus = 'pericial' | 'declarado' | 'vencido';

export function fraudRecordStatus(a: FraudRecord): FraudRecordStatus {
  if (!a.inForce) {
    return 'vencido';
  }
  return a.scores ? 'pericial' : 'declarado';
}

const STATUS_LABELS: Record<FraudRecordStatus, string> = {
  pericial: 'Vigente · con respaldo pericial',
  declarado: 'Vigente · sin respaldo pericial',
  vencido: 'Fuera de la ventana de vigencia',
};

export function fraudRecordStatusLabel(a: FraudRecord): string {
  return STATUS_LABELS[fraudRecordStatus(a)];
}

// Tone follows actual weight: `danger` only when the record feeds the engine.
const STATUS_TONES: Record<FraudRecordStatus, StatusTone> = {
  pericial: 'danger',
  declarado: 'warning',
  vencido: 'neutral',
};

export function fraudRecordStatusTone(a: FraudRecord): StatusTone {
  return STATUS_TONES[fraudRecordStatus(a)];
}

const STATUS_EFFECTS: Record<FraudRecordStatus, string> = {
  pericial: 'Suma al nivel de riesgo y puede impedir la vía rápida.',
  declarado: 'No suma al nivel de riesgo: se muestra como alerta porque no tuvo peritaje detrás.',
  vencido: 'Ya no pesa: pasó la ventana de vigencia que configuró la aseguradora.',
};

export function fraudRecordEffect(a: FraudRecord): string {
  return STATUS_EFFECTS[fraudRecordStatus(a)];
}

export interface RegisterFraudRecordRequest {
  source: FraudRecordOrigin;
  reason: string;
}

/** Same minimum cases-service validates. */
export const FRAUD_RECORD_REASON_MIN = 20;
