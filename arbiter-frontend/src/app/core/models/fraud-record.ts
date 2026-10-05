import { StatusTone } from './status-tone';

/** Mirrors common-lib's FraudRecordSource enum. */
export type FraudRecordOrigin = 'EXPERT_BACKED' | 'ANALYST_DECLARED';

const ORIGIN_LABELS: Record<FraudRecordOrigin, string> = {
  EXPERT_BACKED: 'Con respaldo pericial',
  ANALYST_DECLARED: 'Declarado por el analista',
};

export function fraudRecordOriginLabel(value: string): string {
  return (ORIGIN_LABELS as Record<string, string>)[value] ?? value;
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
 * - `expertBacked`: in force and expert-backed; the only one that scores and can veto Fast Track.
 * - `declared`: in force without an expert report; shown, not counted.
 * - `expired`: outside the window; still shown, since "there was one" differs from "none".
 */
export type FraudRecordStatus = 'expertBacked' | 'declared' | 'expired';

export function fraudRecordStatus(a: FraudRecord): FraudRecordStatus {
  if (!a.inForce) {
    return 'expired';
  }
  return a.scores ? 'expertBacked' : 'declared';
}

const STATUS_LABELS: Record<FraudRecordStatus, string> = {
  expertBacked: 'Vigente · con respaldo pericial',
  declared: 'Vigente · sin respaldo pericial',
  expired: 'Fuera de la ventana de vigencia',
};

export function fraudRecordStatusLabel(a: FraudRecord): string {
  return STATUS_LABELS[fraudRecordStatus(a)];
}

// Tone follows actual weight: `danger` only when the record feeds the engine.
const STATUS_TONES: Record<FraudRecordStatus, StatusTone> = {
  expertBacked: 'danger',
  declared: 'warning',
  expired: 'neutral',
};

export function fraudRecordStatusTone(a: FraudRecord): StatusTone {
  return STATUS_TONES[fraudRecordStatus(a)];
}

const STATUS_EFFECTS: Record<FraudRecordStatus, string> = {
  expertBacked: 'Suma al nivel de riesgo y puede impedir la vía rápida.',
  declared: 'No suma al nivel de riesgo: se muestra como alerta porque no tuvo peritaje detrás.',
  expired: 'Ya no pesa: pasó la ventana de vigencia que configuró la aseguradora.',
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
