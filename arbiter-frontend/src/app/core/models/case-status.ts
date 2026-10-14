import { StatusTone } from './status-tone';

// Mirrors common-lib's CaseStatus enum; cases-service sends it as a string in `status`.
export type CaseStatus =
  | 'PENDING_CLASSIFICATION'
  | 'PENDING_ANALYST_REVIEW'
  | 'CLASSIFICATION_FAILED'
  | 'AWAITING_DOCUMENTATION'
  | 'PENDING_EXPERT_REPORT'
  | 'PENDING_REPAIR'
  | 'APPROVED'
  | 'REJECTED'
  | 'LAPSED'
  | 'PRESCRIBED'
  | 'WITHDRAWN'
  | 'VOIDED';

const LABELS: Record<CaseStatus, string> = {
  PENDING_CLASSIFICATION: 'Pendiente de clasificación',
  PENDING_ANALYST_REVIEW: 'Pendiente de revisión',
  CLASSIFICATION_FAILED: 'Clasificación fallida',
  AWAITING_DOCUMENTATION: 'Falta documentación',
  PENDING_EXPERT_REPORT: 'Derivado a peritaje',
  PENDING_REPAIR: 'Derivado a reparación',
  APPROVED: 'Aprobado',
  REJECTED: 'Rechazado',
  LAPSED: 'Caducado',
  PRESCRIBED: 'Prescripto',
  WITHDRAWN: 'Desistido',
  VOIDED: 'Baja por error',
};

export function caseStatusLabel(value: string): string {
  return (LABELS as Record<string, string>)[value] ?? value;
}

/**
 * The status as the desk reads it. A case whose amount awaits the supervisor's sign-off stays in
 * PENDING_ANALYST_REVIEW, and "Pendiente de revisión" would tell the analyst it is theirs to act on.
 */
export function workStatusLabel(status: string, settlementStatus?: string | null): string {
  return status === 'PENDING_ANALYST_REVIEW' && settlementStatus === 'PENDING_AUTHORIZATION'
    ? 'Esperando al referente'
    : caseStatusLabel(status);
}

/** Mirrors `case_status.is_final` and common-lib's `CaseStatus.terminal()`. */
export const FINAL_STATUSES: readonly CaseStatus[] = [
  'APPROVED',
  'REJECTED',
  'LAPSED',
  'PRESCRIBED',
  'WITHDRAWN',
  'VOIDED',
];

export function isFinalStatus(value: string): boolean {
  return (FINAL_STATUSES as readonly string[]).includes(value);
}

/**
 * Mirrors common-lib's `CaseStatus.pausingTheTerm()`: the art. 56 term is frozen while the case
 * waits on someone outside the insurer, and recalculated in full when it leaves the pause.
 */
const PAUSING_STATUSES: readonly CaseStatus[] = [
  'AWAITING_DOCUMENTATION',
  'PENDING_EXPERT_REPORT',
  'PENDING_REPAIR',
];

export function isPausingStatus(value: string): boolean {
  return (PAUSING_STATUSES as readonly string[]).includes(value);
}

const TONES: Record<CaseStatus, StatusTone> = {
  PENDING_CLASSIFICATION: 'info',
  PENDING_ANALYST_REVIEW: 'info',
  CLASSIFICATION_FAILED: 'warning',
  AWAITING_DOCUMENTATION: 'warning',
  // Waiting on an external third party with nothing for the analyst to do, so not a warning.
  PENDING_EXPERT_REPORT: 'info',
  PENDING_REPAIR: 'info',
  APPROVED: 'ok',
  REJECTED: 'danger',
  LAPSED: 'danger',
  PRESCRIBED: 'danger',
  // Closed without a verdict: neither good nor bad news, so no accent.
  WITHDRAWN: 'neutral',
  VOIDED: 'neutral',
};

export function caseStatusTone(value: string): StatusTone {
  return (TONES as Record<string, StatusTone>)[value] ?? 'neutral';
}

/** Fraud gauge text when there is no `riskBand`, telling apart why it is missing. */
export function riskBandEmptyLabel(status: string, classification: string | null): string {
  if (status === 'PENDING_CLASSIFICATION') {
    return 'En proceso';
  }
  if (classification === 'FAST_TRACK') {
    return 'No aplica · Fast Track';
  }
  return 'Sin evaluar';
}
