import { RiskBand } from '../../../core/models/risk-band';

/** Not `MetricCount`: here the label can be null (a case never scored has no band). */
export interface FraudBucket {
  label: string | null;
  count: number;
}

// Mirrors reports-service DTOs (FraudReport / FraudReportRow / FraudSummary).

/** Why a case shows up in the report. */
export type FraudSignal = 'HIGH_RISK_SCORE' | 'FORENSIC_INCONSISTENCY' | 'DOCUMENT_INCONSISTENCY';

export interface FraudReportRow {
  caseId: number;
  insuredName: string;
  insuredDni: string;
  branch: string;
  claimCause: string;
  reportedAt: string;
  /** null when scoring never ran; shown as "Sin evaluar", never as low risk. */
  riskBand: RiskBand | null;
  signals: FraudSignal[];
  /**
   * The insured's claims in the 12 months up to this one, included (1 = no others). Context, NOT a
   * signal: it already weighs in the score via `claim_frequency`.
   */
  claimsInWindow: number;
  suspiciousImages: number;
  /** Finding of the `document_inconsistency` factor; null if none or the factor is inactive. */
  documentInconsistencyNote: string | null;
  status: string;
  /** Determined by the analyst, not the risk band. */
  fraudDetermined: boolean;
  expertBacked: boolean;
}

export interface FraudSummary {
  /**
   * Every claim of the period and branch, flagged or not: the denominator of both rates. The
   * alert-level filter doesn't change it.
   */
  totalClaims: number;
  flagged: number;
  /** 0..1 fraction; null when there is nothing to divide by. */
  flaggedRate: number | null;
  multiSignal: number;
  fraudDetermined: number;
  /**
   * `fraudDetermined / totalClaims`. Lags on purpose: recent claims are still open, so the current
   * month reads low and rises as they close.
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
  /** Same aggregates over the preceding period of equal length. */
  previousSummary: FraudSummary;
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
 * Takes the row, not the band: the gauge is drawn only when the score alerted. A low score on a case
 * listed for another signal would read as "nothing here"; those get {@link alertEmptyLabel}.
 */
export function riskGaugeBand(row: FraudReportRow): 1 | 2 | 3 | 4 | null {
  return scoreAlerted(row) && row.riskBand !== null ? GAUGE_BANDS[row.riskBand] : null;
}

/** Only the two top bands fire the signal. */
export function scoreAlerted(row: FraudReportRow): boolean {
  return row.signals.includes('HIGH_RISK_SCORE');
}

/** "Sin evaluar": scoring never ran. "No alertó": it ran and flagged nothing. */
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
 * Each signal with its magnitude; the document signal carries its own rationale verbatim.
 * Keep in sync with ReportLabels.signals() in reports-service.
 */
export function indicators(row: FraudReportRow): string[] {
  return row.signals.map((signal) => {
    switch (signal) {
      case 'HIGH_RISK_SCORE':
        return 'Score de riesgo alto';
      case 'FORENSIC_INCONSISTENCY':
        return row.suspiciousImages === 1
          ? '1 imagen con coincidencia'
          : `${row.suspiciousImages} imágenes con coincidencia`;
      case 'DOCUMENT_INCONSISTENCY':
        return row.documentInconsistencyNote ?? 'Contradicción con la documentación';
    }
  });
}

const SIGNAL_LABELS: Record<FraudSignal, string> = {
  HIGH_RISK_SCORE: 'Score de riesgo alto',
  FORENSIC_INCONSISTENCY: 'Incoherencias forenses',
  DOCUMENT_INCONSISTENCY: 'Contradicción con la documentación',
};

export function fraudSignalLabel(signal: string): string {
  return (SIGNAL_LABELS as Record<string, string>)[signal] ?? signal;
}
