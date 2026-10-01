import { StatusTone } from './status-tone';

// Mirrors common-lib's ExpertVerdict enum.
export type ExpertVerdict = 'FRAUD_CONFIRMED' | 'FRAUD_DISCARDED' | 'INCONCLUSIVE';

const VERDICT_LABELS: Record<ExpertVerdict, string> = {
  FRAUD_CONFIRMED: 'Fraude confirmado',
  FRAUD_DISCARDED: 'Fraude descartado',
  INCONCLUSIVE: 'No concluyente',
};

export function verdictLabel(value: string): string {
  return (VERDICT_LABELS as Record<string, string>)[value] ?? value;
}

const VERDICT_TONES: Record<ExpertVerdict, StatusTone> = {
  FRAUD_CONFIRMED: 'danger',
  FRAUD_DISCARDED: 'ok',
  INCONCLUSIVE: 'warning',
};

export function verdictTone(value: string): StatusTone {
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

/** Mirrors ServiceProviderResponse.BranchRef. */
export interface RamoRef {
  id: number;
  name: string;
}

/** Mirrors ServiceProviderResponse. */
export interface Proveedor {
  id: number;
  name: string;
  email: string;
  zone: string | null;
  /** Empty = generalist (covers every branch). */
  branches: RamoRef[];
}

export function ramosLabel(branches: RamoRef[]): string {
  return branches.length === 0 ? 'Todos los ramos' : branches.map((b) => b.name).join(', ');
}

/**
 * Mirrors DerivationOptionsResponse. `eligible` combines the insurer's minimum amount with provider
 * availability; both amounts come so the UI can explain a "no".
 */
export interface DerivationOptions {
  eligible: boolean;
  /** The insurer's rule lets this case go to this kind of provider, whether or not there is one. */
  allowedByRule: boolean;
  minClaimedAmount: number | null;
  claimedAmount: number | null;
  providers: Proveedor[];
}

/** Mirrors CaseReferralResponse. Before the report, `verdict` and `reportReceivedAt` are null. */
export interface Derivacion {
  id: number;
  providerName: string;
  providerEmail: string;
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
