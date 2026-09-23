import { isTypedDate } from '../../../core/util/datetime';
import { percentagePoints, trendText } from '../../../core/util/trend';

/** Mirrors reports-service ResolutionReportRow. */
export interface ResolutionReportRow {
  caseId: number;
  insuredName: string;
  insuredDni: string;
  branch: string;
  claimCause: string;
  reportedAt: string;
  resolvedAt: string;
  totalMinutes: number;
  /** Part of that time spent waiting on a third party. */
  waitingMinutes: number;
  classification: string | null;
  /** APPROVE/REJECT, or the older APROBAR/RECHAZAR spelling still present in older rows. */
  analystDecision: string | null;
  finalStatus: string;
  analystName: string | null;
}

/** Mirrors reports-service MetricCount. */
export interface MetricCount {
  label: string;
  count: number;
}

/**
 * Computed by the backend over the same rows, so totals never contradict the table. Rates are 0..1
 * fractions and null (unknown, not zero) when there was nothing to divide.
 */
export interface ResolutionSummary {
  totalCases: number;
  /** The rest are lapsed cases. */
  decidedCases: number;
  /** Over DECIDED cases only, like the dashboard: a lapsed case measures the insured's silence. */
  averageMinutes: number | null;
  /** Part of that average spent waiting on a third party. */
  averageWaitingMinutes: number | null;
  fastTrackCases: number;
  fastTrackRate: number | null;
  /** `label` is a CaseStatus literal. */
  byStatus: MetricCount[];
  byClaimCause: MetricCount[];
}

export type TimelineGranularity = 'DAY' | 'WEEK' | 'MONTH';

/**
 * Two populations on purpose: `resolved` counts everything closed, `averageMinutes` only decided
 * cases (same as the summary card). The volume bar keeps a thin average from looking significant.
 */
export interface ResolutionTimelinePoint {
  /** First calendar day covered by the point (ISO), in the insurer's time zone. */
  bucket: string;
  resolved: number;
  decided: number;
  /** null when nothing was decided in the bucket: unknown, not zero. */
  averageMinutes: number | null;
}

export interface ResolutionReport {
  from: string;
  to: string;
  /** The branch filter by name, resolved by the backend; null means every branch. */
  branch: string | null;
  claimCause: string | null;
  generatedAt: string;
  summary: ResolutionSummary;
  /** Same summary for the preceding equal-length period and same filters, from the backend. */
  previousSummary: ResolutionSummary;
  /** Chosen by the backend from the period length. */
  granularity: TimelineGranularity;
  timeline: ResolutionTimelinePoint[];
  rows: ResolutionReportRow[];
}

/** `branchId` null and `claimCause` empty each mean "every one". */
export interface ResolutionReportParams {
  from: string;
  to: string;
  branchId: number | null;
  claimCause: string;
}

export type ReportFormat = 'CSV' | 'PDF';

/** Same cap as reports-service (ResolutionReportService.MAX_PERIOD_DAYS): a year, leap day included. */
export const MAX_PERIOD_DAYS = 366;

const MINUTES_PER_HOUR = 60;
const MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;
const MS_PER_DAY = 86_400_000;

/**
 * Own time vs. third-party waiting, only from one hour of waiting (below that it's a case passing
 * briefly through a waiting status). Same rule as the dashboard.
 */
export function waitingBreakdown(summary: ResolutionSummary): string {
  const total = summary.averageMinutes;
  const waiting = summary.averageWaitingMinutes;
  if (total === null || waiting === null || waiting < MINUTES_PER_HOUR) {
    return '';
  }
  return `${formatDuration(Math.max(total - waiting, 0))} de gestión · ${formatDuration(waiting)} esperando a terceros`;
}

/** "45 min", "3 h 20 min", "2 d 5 h", like the exported PDF; rounded first. */
export function formatDuration(value: number): string {
  const minutes = Math.round(value);
  if (minutes < MINUTES_PER_HOUR) {
    return `${minutes} min`;
  }
  if (minutes < MINUTES_PER_DAY) {
    const hours = Math.floor(minutes / MINUTES_PER_HOUR);
    const rest = minutes % MINUTES_PER_HOUR;
    return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
  }
  const days = Math.floor(minutes / MINUTES_PER_DAY);
  const hours = Math.floor((minutes % MINUTES_PER_DAY) / MINUTES_PER_HOUR);
  return hours === 0 ? `${days} d` : `${days} d ${hours} h`;
}

/**
 * Both spellings on purpose: older rows hold APROBAR/RECHAZAR. Unknown values are shown as they
 * came rather than hidden behind "Sin decisión".
 */
export function decisionLabel(decision: ResolutionReportRow['analystDecision']): string {
  switch (decision) {
    case 'APPROVE':
    case 'APROBAR':
      return 'Aprobó';
    case 'REJECT':
    case 'RECHAZAR':
      return 'Rechazó';
    case null:
      return 'Sin decisión';
    default:
      return decision;
  }
}

/** Same rules the backend enforces; checked here only to spare a round trip bound to return 400. */
export function periodError(from: string, to: string): string | null {
  if (!isTypedDate(from) || !isTypedDate(to)) {
    return 'Completá las dos fechas del período.';
  }
  if (from > to) {
    return 'La fecha «desde» no puede ser posterior a «hasta».';
  }
  // UTC on purpose, same as addDays(): local midnights across a DST change are 23 or 25 hours apart.
  const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / MS_PER_DAY + 1;
  if (days > MAX_PERIOD_DAYS) {
    return 'El período no puede superar un año.';
  }
  return null;
}

/**
 * No verdict (volume is neither better nor worse) and no minimum comparison base: an absolute
 * difference stays exact on a small base, only percentages become noise.
 */
export function resolvedTrend(summary: ResolutionSummary, previous: ResolutionSummary): string {
  return trendText({
    current: summary.totalCases,
    previous: previous.totalCases,
    format: (size) => `${size}`,
    good: 'neither',
  });
}

/**
 * In percentage points; more is better. Silent when the previous period closed too few cases
 * (`DEFAULT_MIN_COMPARISON_BASE` in core/util/trend).
 */
export function fastTrackTrend(summary: ResolutionSummary, previous: ResolutionSummary): string {
  return trendText({
    current: summary.fastTrackRate,
    previous: previous.fastTrackRate,
    format: percentagePoints,
    good: 'up',
    base: previous.totalCases,
  });
}

/** Longer is worse. The comparison base is the previous period's DECIDED cases, as on the card. */
export function resolutionTimeTrend(
  summary: ResolutionSummary,
  previous: ResolutionSummary,
): string {
  return trendText({
    current: summary.averageMinutes,
    previous: previous.averageMinutes,
    format: formatDuration,
    good: 'down',
    base: previous.decidedCases,
  });
}
