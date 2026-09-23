import { HardRule } from '../../features/admin/hard-rules.service';
import { RiskBand } from './risk-band';

// Rules configuration edited by the referent, organized per branch (ramo). Fraud scoring is not
// here: it is a single per-insurer config (see ScoringConfig).

/** Mirrors the backend SettlementBasis enum. */
export type SettlementBasis = 'SUM_INSURED' | 'LESSER_OF_SUM_AND_REPLACEMENT';

/** Mirrors the backend SettlementFormula enum. */
export type SettlementFormula = 'TOTAL_LOSS' | 'REPAIR';

export interface Coverage {
  id: string;
  name: string;
  /** General conditions clause code (e.g. "340"). */
  clause: string;
  /** Null when it comes from the policy. */
  insuredAmount: number | null;
  /** Fraction (0..1) of the amount. */
  deductibleRatio: number | null;
  reportingWindowDays: number | null;
  /** Per year and policy. */
  maxAnnualClaims: number | null;
  /** Days after policy start during which the coverage does not apply yet. null = none. */
  waitingPeriodDays: number | null;
  coversFamilyGroup: boolean;
  claimExhaustsCoverage: boolean;
  /** Total loss extinguishes the policy; repair does not. */
  settlementFormula: SettlementFormula;
  /** Only applies to total loss. */
  settlementBasis: SettlementBasis;
  /** Fraction 0..1 of the cap paid from the year's second event on. null = no reduction. */
  secondEventRatio: number | null;
  /** Total loss only: deduct premium installments not yet due. */
  deductPendingInstallments: boolean;
  deductOverdueBalance: boolean;
  /** Free-text exclusions fed to the LLM prompt. */
  exclusions: string[];
  /**
   * Claim cause ids this coverage does not cover: a hard exclusion evaluated by the rules engine
   * (COVERAGE_EXCLUSION), not the LLM. Loaded from rules-service with the coverage detail.
   */
  excludedClaimCauseIds?: number[];
  /**
   * Hard temporal rules the insurer has active for this coverage (coverage window, waiting
   * period, deadlines, events cap, arrears). They're each rule's switch, not its threshold: the
   * thresholds are the fields above, except the police-report deadline, which has no column of
   * its own and travels inside its own rule. Optional: `HardRulesService` loads them from
   * rules-service.
   */
  hardRules?: HardRule[];
}

/** Deterministic Fast Track gate, configured per branch. */
export interface FastTrackConfig {
  enabled: boolean;
  minPolicyAgeMonths: number | null;
  maxPriorClaims: number | null;
  priorClaimsWindowMonths: number | null;
  /** Fraction (0..1) of the sum insured. */
  maxClaimedAmountRatio: number | null;
  requiresUpToDatePolicy: boolean;
  requiredDocumentTypes: string[];
  criteria: string[];
}

export interface FactorWeight {
  factorId: string;
  weight: number;
}

/** A band applies when the normalized score is >= minScoreInclusive (0..1). */
export interface RiskBandCut {
  band: RiskBand;
  minScoreInclusive: number;
}

/** Single per-insurer fraud scoring config (not per branch), served at `/api/v1/rules/scoring`. */
export interface ScoringConfig {
  enabled: boolean;
  /**
   * Whether Fast Track still runs the heavy analysis (OCR + image fraud) so the score is complete.
   * It never vetoes Fast Track: it only decides how much analysis runs.
   */
  fullAnalysisOnFastTrack: boolean;
  factors: FactorWeight[];
  bands: RiskBandCut[];
}

export interface RamoRules {
  id: string;
  name: string;
  coverages: Coverage[];
  /**
   * How many coverages the ramo has, from `/coverages/summary` — accurate for every ramo up
   * front, unlike `coverages.length`, which stays 0 until the referente actually selects the
   * ramo and its full detail loads. The sidebar badge reads this, not `coverages.length`.
   */
  coverageCount: number;
  commonExclusions: string[];
  /** claimCauseId → required document type codes. */
  requiredDocumentsByClaimCause: { [claimCauseId: number]: string[] };
  businessRules: string[];
  fastTrack: FastTrackConfig;
}

/** Ids match the backend RiskFactorIds contract. */
export interface RiskFactorDef {
  id: string;
  label: string;
}

export const RISK_FACTORS: RiskFactorDef[] = [
  { id: 'amount_ratio', label: 'Relación monto reclamado / suma asegurada' },
  { id: 'claim_frequency', label: 'Frecuencia de siniestros del asegurado' },
  { id: 'policy_standing', label: 'Situación de la póliza (vigencia / mora)' },
  { id: 'purchase_to_report_time', label: 'Tiempo entre compra y denuncia' },
  { id: 'document_inconsistency', label: 'Inconsistencias en la documentación' },
  { id: 'image_reuse', label: 'Imagen reutilizada de otra denuncia' },
  { id: 'image_web_match', label: 'Imagen publicada en la web' },
  { id: 'fraud_history', label: 'Antecedente de fraude del asegurado' },
];

export function riskFactorLabel(id: string): string {
  return RISK_FACTORS.find((f) => f.id === id)?.label ?? id;
}

export interface DocumentTypeDef {
  code: string;
  label: string;
}

export const DOCUMENT_TYPES: DocumentTypeDef[] = [
  { code: 'police_report', label: 'Denuncia policial' },
  { code: 'purchase_proof', label: 'Comprobante de compra (preexistencia)' },
  { code: 'imei_deregistration', label: 'Baja de IMEI (ENACOM)' },
  { code: 'last_connection', label: 'Captura de última conexión' },
  { code: 'repair_quote', label: 'Presupuesto de reparación' },
  { code: 'item_photo', label: 'Foto del bien' },
  // Uploaded by the analyst from the expert's report, not by the insured.
  { code: 'expert_report', label: 'Informe de peritaje' },
];

export function documentTypeLabel(code: string): string {
  return DOCUMENT_TYPES.find((d) => d.code === code)?.label ?? code;
}

/** Backend reasons embed document codes ("…: police_report"); swaps them for their labels. */
export function conLabelesDeDocumento(reason: string): string {
  return DOCUMENT_TYPES.reduce(
    // String.raw: in a plain template literal `\b` is a backspace, not a word boundary.
    (texto, d) => texto.replace(new RegExp(String.raw`\b${d.code}\b`, 'g'), d.label),
    reason,
  );
}
