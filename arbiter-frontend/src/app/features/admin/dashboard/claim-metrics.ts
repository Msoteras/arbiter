// Mirrors reports-service DTOs (ar.edu.utn.frba.arbiter.reports.dto).

/** 7, 30 and 90-day windows ending today. */
export type MetricsRange = 'WEEK' | 'MONTH' | 'QUARTER';

/** Chosen by the backend from the period length. */
export type TimelineGranularity = 'DAY' | 'WEEK' | 'MONTH';

/**
 * `label` is null where the dimension doesn't apply yet (not classified, not scored). Those are still
 * shown, otherwise the slices wouldn't add up to the total.
 */
export interface MetricCount {
  label: string | null;
  count: number;
}

export interface TimelinePoint {
  /** First calendar day covered by the point (ISO), in the insurer's time zone. */
  bucket: string;
  reported: number;
  resolved: number;
}

/**
 * Two different populations: `reported*` counts claims reported in the period, `resolved*` those
 * closed in it. Rates are 0..1 fractions and null (unknown, not 0%) when there's nothing to divide.
 */
export interface MetricsSummary {
  reportedCases: number;
  fastTrackCases: number;
  resolvedCases: number;
  approvedCases: number;
  rejectedCases: number;
  /** Excluded from rates and averages: nobody decided them. */
  lapsedCases: number;
  approvalRate: number | null;
  rejectionRate: number | null;
  fastTrackRate: number | null;
  /** From report to analyst decision, over cases decided in the period. */
  averageResolutionHours: number | null;
  /**
   * Part of that time spent waiting on third parties (documents, expert report, repair), which
   * pauses the legal deadline. The insurer's own time is the difference between the two.
   */
  averageWaitingHours: number | null;
}

/** null = all. */
export interface MetricsFilter {
  branchId: number | null;
  analystId: number | null;
}

/**
 * Follows claims REPORTED in the period forward, even past its end. A different population from
 * `MetricsSummary`, which counts what CLOSED in the period.
 */
export interface IntakeFunnel {
  reported: number;
  /** Lower than `reported` when there was Fast Track: the LLM never runs on those. */
  analyzed: number;
  decided: number;
  fastTrack: number;
  /** Without a final status as of today. */
  stillOpen: number;
}

/**
 * How often the analyst ended where the LLM pointed. Only actionable recommendations count: manual
 * review and missing documentation say nothing about approving or rejecting.
 */
export interface RecommendationAgreement {
  decided: number;
  agreed: number;
  /** null when nothing had an actionable recommendation: unknown, not zero. */
  rate: number | null;
}

/**
 * A management goal set by the referente, not the legal deadline. When disabled the dashboard shows
 * the average without comparing it, same as when rules-service doesn't answer.
 */
export interface ResolutionTarget {
  enabled: boolean;
  targetDays: number | null;
  /**
   * Cases decided in the period that took longer, measured by handling time (third-party waits
   * excluded). The card's headline is total time, so it must say "de gestión" or it reads as a bug.
   */
  exceeded: number;
}

/** Art. 56 legal deadline compliance: the only regulatory metric, kept apart from the goal. */
export interface LegalDeadline {
  decided: number;
  onTime: number;
  /** null without decisions: unknown, not 0%. */
  rate: number | null;
}

/** Cases closed in the period that were ever reopened, counted per case (not per reopening). */
export interface ReopeningRate {
  resolved: number;
  reopened: number;
  rate: number | null;
}

/**
 * Amounts are strings: backend BigDecimals, kept as text to avoid JS float precision loss. Only
 * signed settlements count: one awaiting the referente may still be redone for another amount.
 */
export interface SettledAmounts {
  settlements: number;
  settled: string;
  /** null without settlements: no average, not zero. */
  average: string | null;
  claimed: string;
  /** Settlements with a claimed amount (it's optional for the insured). If it doesn't cover them
   *  all, the percentage would compare different populations and isn't shown. */
  claimedCases: number;
  deductible: string;
  installments: string;
  overdue: string;
}

export interface FraudDetection {
  decided: number;
  /** Determined by the analyst, not the risk band. */
  fraudDetermined: number;
  /** Of those, the ones confirmed by an expert report. */
  backedByExpert: number;
  /** Claimed amount of the rejected ones only: nothing was saved on approved ones. */
  amountNotPaid: string;
}

/**
 * Two measurements rather than an estimated "hours saved": multiplying the difference of two averages
 * by the case count is noise at a dozen cases a month.
 */
export interface FastTrackImpact {
  fastTrackDecided: number;
  fastTrackHours: number | null;
  standardDecided: number;
  standardHours: number | null;
}

/**
 * Counted by when the referral was SENT, not answered: anchoring on the answer would drop the
 * pending ones, which are the ones to watch.
 */
export interface DerivationTurnaround {
  /** Raw backend literal (`ESTUDIO_LIQUIDADOR` / `SERVICIO_TECNICO`). */
  providerType: string;
  derived: number;
  answered: number;
  /** Averaged over answered referrals only. */
  averageHours: number | null;
}

export interface ClaimMetrics {
  /** Inclusive (ISO). */
  from: string;
  /** Inclusive (ISO). */
  to: string;
  generatedAt: string;
  granularity: TimelineGranularity;
  filter: MetricsFilter;
  funnel: IntakeFunnel;
  summary: MetricsSummary;
  /** Same summary for the immediately preceding period of equal length, to show trends. */
  previousSummary: MetricsSummary;
  agreement: RecommendationAgreement;
  resolutionTarget: ResolutionTarget;
  legalDeadline: LegalDeadline;
  reopening: ReopeningRate;
  settled: SettledAmounts;
  fraud: FraudDetection;
  fastTrack: FastTrackImpact;
  derivations: DerivationTurnaround[];
  /** Reported in the period, by their CURRENT status. */
  byStatus: MetricCount[];
  byBranch: MetricCount[];
  byClassification: MetricCount[];
  byRiskBand: MetricCount[];
  byBlockingRule: MetricCount[];
  timeline: TimelinePoint[];
}

/**
 * Whole hours under a day, days with one decimal above: Fast Track closes in hours, expert cases in
 * weeks. null (nothing decided) is unknown, not zero.
 */
export function resolutionTimeLabel(hours: number | null | undefined): string {
  if (hours === null || hours === undefined) {
    return '—';
  }
  return hours < 24 ? `${Math.round(hours)} h` : `${(hours / 24).toFixed(1)} d`;
}

export function providerLabel(providerType: string): string {
  switch (providerType) {
    case 'ESTUDIO_LIQUIDADOR':
      return 'Peritaje';
    case 'SERVICIO_TECNICO':
      return 'Servicio técnico';
    default:
      return providerType;
  }
}
