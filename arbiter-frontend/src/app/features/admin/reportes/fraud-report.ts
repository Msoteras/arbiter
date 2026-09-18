import { RiskBand } from '../../../core/models/risk-band';

/**
 * One bar of this report's distributions. It doesn't reuse `MetricCount` from
 * `resolution-report.ts` because here the label really can be null: a case the scoring never ran
 * on has no band, and the backend sends it that way on purpose (see `MetricCount`'s javadoc in
 * reports-service). The resolution report never sees a null — its buckets are statuses and claim
 * causes, which are always there.
 */
export interface FraudBucket {
  label: string | null;
  count: number;
}

/**
 * Mirror of reports-service's DTOs (FraudReport / FraudReportRow / FraudSummary).
 * Enums arrive as literals; turning them into Spanish is the frontend's job.
 */

/** Mirror of FraudSignal: why a case shows up in the report. */
export type FraudSignal = 'HIGH_RISK_SCORE' | 'REPEAT_CLAIMANT' | 'FORENSIC_INCONSISTENCY';

export interface FraudReportRow {
  caseId: number;
  insuredName: string;
  insuredDni: string;
  branch: string;
  claimCause: string;
  reportedAt: string;
  /**
   * The alert level. Null when the scoring never ran on the case (Fast Track, or still being
   * classified): it is still listed if another signal fired, and the gauge shows it as "Sin
   * evaluar" rather than as low risk, which would be the opposite of what happened.
   */
  riskBand: RiskBand | null;
  signals: FraudSignal[];
  /** The insured's claims in the 12 months up to this one, this one included: 1 = no others. */
  claimsInWindow: number;
  suspiciousImages: number;
  /** CaseStatus literal. */
  status: string;
  /** A human decision, not the model's band: the score suggests, the analyst determines. */
  fraudDetermined: boolean;
  expertBacked: boolean;
}

export interface FraudSummary {
  /**
   * Every claim of the period and branch, flagged or not: the denominator of both rates. It is a
   * figure of its own because "8 flagged cases" says nothing until you know whether it is 8 out of
   * 20 or out of 2000. The alert-level filter doesn't move it — with "Crítico" selected, the rate
   * still answers what share of the period is critical.
   */
  totalClaims: number;
  flagged: number;
  /** `flagged / totalClaims`, a 0..1 fraction; null when there is nothing to divide by. */
  flaggedRate: number | null;
  /** With two or more coinciding signals — the reason the report exists. */
  multiSignal: number;
  fraudDetermined: number;
  /**
   * `fraudDetermined / totalClaims`. **It lags on purpose**: the population is the period's claims,
   * and the most recent ones are still open, so the current month reads low and rises as those
   * cases close.
   */
  fraudRate: number | null;
  backedByExpert: number;
  /** The buckets of {@link alertLevelLabel}: `CRITICAL`, `HIGH`, `NOT_FLAGGED` and `NOT_SCORED`. */
  byAlertLevel: FraudBucket[];
  /** The buckets overlap: a case with two signals counts in both. */
  bySignal: FraudBucket[];
}

export interface FraudReport {
  from: string;
  to: string;
  branch: string | null;
  riskBand: RiskBand | null;
  generatedAt: string;
  summary: FraudSummary;
  rows: FraudReportRow[];
}

export interface FraudReportParams {
  from: string;
  to: string;
  branchId: number | null;
  /** Empty = every band. */
  riskBand: string;
}

/** Band → `app-fraud-gauge` segment (1..4, Low to Critical). */
const GAUGE_BANDS: Record<RiskBand, 1 | 2 | 3 | 4> = {
  LOW: 1,
  MEDIUM: 2,
  HIGH: 3,
  CRITICAL: 4,
};

/**
 * The gauge is drawn only when the score actually alerted, which is why it takes the whole row and
 * not the band: a low score is NOT an indicator of fraud, so painting it under "Nivel de alerta"
 * would read as "nothing here" on precisely a case listed because another signal did find
 * something. For those it returns null and the gauge shows {@link alertEmptyLabel}.
 */
export function riskGaugeBand(row: FraudReportRow): 1 | 2 | 3 | 4 | null {
  return scoreAlerted(row) && row.riskBand !== null ? GAUGE_BANDS[row.riskBand] : null;
}

/** The score is an alert only in the two top bands, which is when the signal fires. */
export function scoreAlerted(row: FraudReportRow): boolean {
  return row.signals.includes('HIGH_RISK_SCORE');
}

/**
 * What the cell says when the score didn't alert. "Sin evaluar" and "No alertó" are not the same:
 * in the first the engine never ran (Fast Track, or a failed classification), in the second it ran
 * and flagged nothing — and that is a different operational fact.
 */
export function alertEmptyLabel(row: FraudReportRow): string {
  return row.riskBand === null ? 'Sin evaluar' : 'No alertó';
}

const ALERT_LEVEL_LABELS: Record<string, string> = {
  CRITICAL: 'Crítico',
  HIGH: 'Alto',
  NOT_FLAGGED: 'No alertó',
  NOT_SCORED: 'Sin evaluar',
};

export function alertLevelLabel(bucket: string | null): string {
  return bucket === null ? 'Sin evaluar' : (ALERT_LEVEL_LABELS[bucket] ?? bucket);
}

/**
 * What goes in the "Indicadores" column: each signal with its magnitude, which is what is
 * actionable. "Score de riesgo alto" only says the engine flagged it; "3 denuncias en 12 meses"
 * says what to look at. Mirror of ReportLabels.signals() in reports-service; keep the two in step.
 */
export function indicators(row: FraudReportRow): string[] {
  return row.signals.map((signal) => {
    switch (signal) {
      case 'HIGH_RISK_SCORE':
        return 'Score de riesgo alto';
      case 'REPEAT_CLAIMANT':
        return `${row.claimsInWindow} denuncias en 12 meses`;
      case 'FORENSIC_INCONSISTENCY':
        return row.suspiciousImages === 1
          ? '1 imagen con coincidencia'
          : `${row.suspiciousImages} imágenes con coincidencia`;
    }
  });
}

const SIGNAL_LABELS: Record<FraudSignal, string> = {
  HIGH_RISK_SCORE: 'Score de riesgo alto',
  REPEAT_CLAIMANT: 'Denuncias repetidas',
  FORENSIC_INCONSISTENCY: 'Incoherencias forenses',
};

export function fraudSignalLabel(signal: string): string {
  return (SIGNAL_LABELS as Record<string, string>)[signal] ?? signal;
}
