import { isTypedDate } from '../../../core/util/datetime';

/**
 * Mirror of reports-service's ResolutionReportRow. Enums arrive as literals (CaseStatus,
 * Classification); turning them into Spanish labels is the frontend's job (estado.ts,
 * clasificacion.ts).
 */
export interface ResolutionReportRow {
  caseId: number;
  insuredName: string;
  insuredDni: string;
  branch: string;
  claimCause: string;
  reportedAt: string;
  resolvedAt: string;
  totalMinutes: number;
  classification: string | null;
  /** APPROVE/REJECT, or the older APROBAR/RECHAZAR spelling still present in older rows. */
  analystDecision: string | null;
  finalStatus: string;
  analystName: string | null;
}

export interface ResolutionReport {
  from: string;
  to: string;
  claimCause: string | null;
  generatedAt: string;
  rows: ResolutionReportRow[];
}

/** `claimCause` empty means every claim cause. */
export interface ResolutionReportParams {
  from: string;
  to: string;
  claimCause: string;
}

export type ReportFormat = 'CSV' | 'PDF';

/** Same cap as reports-service (ResolutionReportService.MAX_PERIOD_DAYS): a year, leap day included. */
export const MAX_PERIOD_DAYS = 366;

const MINUTES_PER_HOUR = 60;
const MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;
const MS_PER_DAY = 86_400_000;

/** "45 min", "3 h 20 min", "2 d 5 h" — the same format the exported PDF uses. */
export function formatDuration(minutes: number): string {
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
 * Both spellings on purpose: the backend normalizes new decisions to APPROVE/REJECT, but older
 * rows hold APROBAR/RECHAZAR, and mapping only the English ones showed a real decision as "Sin
 * decisión" — the one reading that must never be wrong. Anything else shows as it came, rather
 * than being hidden behind "Sin decisión".
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

/**
 * Why the period can't be requested, or null if it can. The backend enforces the same rules; this
 * only spares a round trip that is bound to come back 400.
 */
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
