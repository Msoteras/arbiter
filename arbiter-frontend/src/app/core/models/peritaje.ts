import { isEstadoFinal } from './estado';
import { StatusTone } from './status-tone';

// Mirrors common-lib's ExpertVerdict enum.
export type ExpertVerdict = 'FRAUD_CONFIRMED' | 'FRAUD_DISCARDED' | 'INCONCLUSIVE';

const VERDICT_LABELS: Record<ExpertVerdict, string> = {
  FRAUD_CONFIRMED: 'Fraude confirmado',
  FRAUD_DISCARDED: 'Fraude descartado',
  INCONCLUSIVE: 'No concluyente',
};

export function veredictoLabel(value: string): string {
  return (VERDICT_LABELS as Record<string, string>)[value] ?? value;
}

const VERDICT_TONES: Record<ExpertVerdict, StatusTone> = {
  FRAUD_CONFIRMED: 'danger',
  FRAUD_DISCARDED: 'ok',
  INCONCLUSIVE: 'warning',
};

export function veredictoTone(value: string): StatusTone {
  return (VERDICT_TONES as Record<string, StatusTone>)[value] ?? 'neutral';
}

export type ProviderType = 'ESTUDIO_LIQUIDADOR' | 'SERVICIO_TECNICO';

const PROVIDER_LABELS: Record<ProviderType, string> = {
  ESTUDIO_LIQUIDADOR: 'Estudio liquidador',
  SERVICIO_TECNICO: 'Servicio técnico',
};

export function providerTypeLabel(value: string): string {
  return (PROVIDER_LABELS as Record<string, string>)[value] ?? value;
}

export const PROVIDER_TYPE_OPTIONS = Object.entries(PROVIDER_LABELS).map(([value, label]) => ({
  value,
  label,
}));

export type RepairOutcome = 'REPAIRED' | 'IRREPARABLE' | 'QUOTE_SENT';

const REPAIR_LABELS: Record<RepairOutcome, string> = {
  REPAIRED: 'Reparado',
  IRREPARABLE: 'Irreparable',
  QUOTE_SENT: 'Presupuesto enviado',
};

export function repairOutcomeLabel(value: string): string {
  return (REPAIR_LABELS as Record<string, string>)[value] ?? value;
}

export const REPAIR_OUTCOME_OPTIONS = Object.entries(REPAIR_LABELS).map(([value, label]) => ({
  value,
  label,
}));

/** Mirrors ExpertFirmResponse. */
export interface Perito {
  id: number;
  name: string;
  email: string;
  zone: string | null;
  /** null = generalist (covers every branch). */
  branchName: string | null;
}

/**
 * Mirrors DerivationOptionsResponse. `eligible` combines the insurer's minimum amount with expert
 * availability; both amounts come so the UI can explain a "no".
 */
export interface OpcionesDerivacion {
  eligible: boolean;
  minClaimedAmount: number | null;
  claimedAmount: number | null;
  firms: Perito[];
}

/** Mirrors ExpertAssessmentResponse. Before the report, `verdict` and `reportReceivedAt` are null. */
export interface Peritaje {
  id: number;
  expertName: string;
  expertEmail: string;
  zone: string | null;
  reason: string;
  derivedAt: string;
  derivedByName: string;
  /** false = the email never went out, so nobody was told. */
  notified: boolean;
  reportReceivedAt: string | null;
  verdict: ExpertVerdict | null;
  repairOutcome: RepairOutcome | null;
  providerType: ProviderType;
  verdictNote: string | null;
  /** Null when the report gives no figure (e.g. confirmed fraud); zero would mean something else. */
  indemnifiableAmount: number | null;
  /** Quoted or invoiced repair cost; null when irreparable. Distinct from `indemnifiableAmount`. */
  repairCost: number | null;
  reportDocumentId: number | null;
}

export interface DerivationResult {
  providerType: ProviderType;
  verdict: ExpertVerdict | null;
  repairOutcome: RepairOutcome | null;
  respondedAt: string;
}

const AWAITING_PROVIDER = ['PENDING_EXPERT_REPORT', 'PENDING_REPAIR'];

export function derivationNote(
  status: string,
  result: DerivationResult | null,
): { label: string; tone: StatusTone } | null {
  if (!result || AWAITING_PROVIDER.includes(status) || isEstadoFinal(status)) {
    return null;
  }
  if (result.providerType === 'SERVICIO_TECNICO') {
    return {
      label: result.repairOutcome
        ? `Volvió del servicio técnico: ${repairOutcomeLabel(result.repairOutcome)}`
        : 'Volvió del servicio técnico',
      tone: 'neutral',
    };
  }
  return {
    label: result.verdict
      ? `Volvió del perito: ${veredictoLabel(result.verdict)}`
      : 'Volvió del perito',
    tone: result.verdict ? veredictoTone(result.verdict) : 'neutral',
  };
}
