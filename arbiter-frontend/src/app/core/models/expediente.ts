import { CauseConsistency } from './cause-consistency';
import { Clasificacion } from './clasificacion';
import { DeadlinePriority } from './deadline-priority';
import { ImageForensicReport } from './forensic';
import { DerivationResult } from './peritaje';
import { PolicySnapshot, RuleResult } from './trazabilidad';

// Mirrors cases-service's StatusTransitionResponse; `fromStatus` is null on the creation row.
export interface StatusTransition {
  fromStatus: string | null;
  toStatus: string;
  actor: 'SYSTEM' | 'INSURED' | 'ANALYST' | 'REFERENT';
  reason: string;
  changedAt: string;
}

/** One factor's contribution to the fraud score (backend RiskBreakdownItem). */
export interface RiskBreakdownItem {
  factorId: string;
  /** Normalized contribution in [0,1]. */
  rawScore: number;
  weight: number;
  /** rawScore * weight. */
  weightedContribution: number;
  rationale: string;
}

/**
 * What the model read from an attachment (backend DocumentAnalysisSummary).
 * A `null` field means "the document does not say", never "mismatch": render it as not applicable.
 */
export interface DocumentAnalysis {
  /** The document requirement slot the attachment fulfils (`police_report`, …). */
  documentType: string;
  transcription: string;
  documentDate: string | null;
  amount: number | null;
  itemDescription: string | null;
  /** Brand alone, apart from `itemDescription`: it is what gets matched against the insured item. */
  brand: string | null;
  model: string | null;
  imei: string | null;
  /** TITULAR | FAMILIAR | TERCERO | DESCONOCIDO. */
  affectedParty: string;
  /** Tampering signals seen by the vision model. Empty is normal and does not prove authenticity. */
  visualFindings: string[];
  /**
   * Any other data the document states that no rule reads. `name` is the model's own wording, so
   * it is displayed as-is and never compared.
   */
  details: ExtractedDetail[];
}

export interface ExtractedDetail {
  name: string;
  value: string;
}

/** Mirrors the backend SettlementStatus enum: the settlement's status, not the case's. */
export type SettlementStatus = 'AUTHORIZED' | 'PENDING_AUTHORIZATION' | 'RETURNED';

// Mirrors cases-service's CaseResponse (GET /api/v1/cases/{id}).
export interface ExpedienteResponse {
  id: number;
  /**
   * Only sent in the insured's cross-insurer list. `insurerSlug` is needed to refetch the case
   * because `id` repeats across insurers.
   */
  insurerSlug?: string | null;
  insurerName?: string | null;
  status: string;
  branch: string;
  product: string;
  claimCause: string;
  /** The coverage the case is filed under — the one `policySnapshot.sumInsured` belongs to. */
  coverage: string | null;
  insuredItem: string;
  insuredId: string;
  /** Resolved by classification-service when classifying; null until then. */
  insuredName: string | null;
  /** Politically exposed person, as self-declared (AML due diligence). Not a fraud signal. */
  pep: boolean;
  policyNumber: string;
  description: string;
  eventDate: string;
  eventLocation: string;
  claimedAmount: number | null;
  riskBand: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL' | null;
  /** Normalized fraud score in [0,1]; null when not scored. */
  riskScore: number | null;
  /** Analyst-only. */
  riskBreakdown: RiskBreakdownItem[] | null;
  /** Analyst-only. Null when it did not run (Fast Track or no image attachments). */
  forensicReport: ImageForensicReport | null;
  /**
   * Analyst id within the insurer (same as `GET /auth/users/analysts`), not the session user id.
   * Null = unassigned.
   */
  assignedAnalystId: number | null;
  assignedAnalystName: string | null;
  analysisClassification: Clasificacion | string;
  analysisConfidence: number;
  /** One element per reason. Empty on Fast Track or before classification. */
  analysisReasons: string[];
  /**
   * Whether the free-text account matches the selected claim cause. Null means "not evaluated"
   * (model did not run), never MATCHES.
   */
  causeConsistency: CauseConsistency | string | null;
  /** Only with CONTRADICTS. */
  suggestedClaimCause: string | null;
  /** Only with CONTRADICTS. */
  causeEvidence: string | null;
  createdAt: string;
  updatedAt: string;
  /** Legal response deadline (art. 56), ISO yyyy-MM-dd. */
  responseDeadline: string;
  deadlinePriority: DeadlinePriority;
  /**
   * Tells apart, in the inbox, a case waiting on the analyst from one awaiting the referent's
   * sign-off: both stay in `PENDING_ANALYST_REVIEW` so the insured never sees the internal step.
   * Only sent in lists; the detail fetches the full settlement from its own endpoint.
   */
  settlementStatus: SettlementStatus | null;
  /** Only in GET /{id}; null in lists. */
  statusHistory: StatusTransition[] | null;
  /** Only in GET /{id}. */
  documentAnalyses: DocumentAnalysis[];
  /**
   * Only in GET /{id}; includes PASS results. Empty = no rule ran; `null` = could not be read,
   * and the UI says so differently.
   */
  ruleResults: RuleResult[] | null;
  /** The policy as the insurer DB returned it at classification time, not the current one. */
  policySnapshot: PolicySnapshot | null;
  /** Only in GET /{id} while under repair. The one referral the insured gets to see. */
  repairProvider: RepairProvider | null;
  lastDerivationResult: DerivationResult | null;
}

export interface RepairProvider {
  name: string;
  email: string;
  zone: string | null;
}
