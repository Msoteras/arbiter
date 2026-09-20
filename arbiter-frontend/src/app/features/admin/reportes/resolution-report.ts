import { isTypedDate } from '../../../core/util/datetime';
import { percentagePoints, trendText } from '../../../core/util/trend';

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
  /** The part of that time the case waited on somebody outside the insurer. */
  waitingMinutes: number;
  classification: string | null;
  /** APPROVE/REJECT, or the older APROBAR/RECHAZAR spelling still present in older rows. */
  analystDecision: string | null;
  finalStatus: string;
  analystName: string | null;
}

/** One bar of a distribution — mirror of reports-service's MetricCount. */
export interface MetricCount {
  label: string;
  count: number;
}

/**
 * The aggregates the report leads with. Computed by the backend over the very rows below them, so
 * the screen never has to add anything up — and never shows a total its own table contradicts.
 *
 * Rates are fractions between 0 and 1 (the percent pipe formats them) and null, not zero, when
 * there was nothing to divide: a period that resolved nothing has an unknown Fast Track share.
 */
export interface ResolutionSummary {
  totalCases: number;
  /** How many of them an analyst decided; the difference are the lapsed ones. */
  decidedCases: number;
  /**
   * Over the DECIDED cases, the same population the dashboard averages: a lapsed case measured the
   * insured's silence, and one of 18 months would swamp a month's average.
   */
  averageMinutes: number | null;
  /** The part of that average spent waiting on a third party; the insurer's own time is the rest. */
  averageWaitingMinutes: number | null;
  fastTrackCases: number;
  fastTrackRate: number | null;
  /** `label` is a CaseStatus literal; estado.ts turns it into Spanish. */
  byStatus: MetricCount[];
  byClaimCause: MetricCount[];
}

/** Ancho de cada punto de la línea de tiempo. */
export type TimelineGranularity = 'DAY' | 'WEEK' | 'MONTH';

/**
 * Un punto de la línea de tiempo: cuánto tardó lo que cerró en ese tramo.
 *
 * Dos poblaciones a propósito, y el gráfico dibuja las dos: `resolved` es todo lo que cerró y
 * `averageMinutes` promedia sólo los decididos — el mismo corte que la tarjeta de arriba, así que
 * un punto de la línea no puede contradecirla. La barra al lado de la línea es lo que evita que el
 * promedio mienta por omisión: uno sobre dos expedientes y otro sobre cuarenta se dibujan igual de
 * alto, y sólo el volumen dice cuál significa algo.
 */
export interface ResolutionTimelinePoint {
  /** Primer día calendario que cubre el punto (ISO), en la zona de la aseguradora. */
  bucket: string;
  resolved: number;
  decided: number;
  /** Null cuando en el tramo no se decidió ninguno: desconocido, no cero. */
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
  /**
   * El mismo resumen del período inmediatamente anterior, de igual largo y con los mismos filtros.
   * Es lo que convierte cada cifra en una dirección, y viene MEDIDO por el backend: la pantalla no
   * resta nada que no le hayan dado.
   */
  previousSummary: ResolutionSummary;
  /** Ancho de cada punto de la línea de tiempo. Lo decide el backend según el largo del período. */
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
 * The breakdown under the average, worded as the dashboard words it: how much of the delay was the
 * insurer's own and how much was waiting on a third party.
 *
 * Only from an hour of waiting up. Below that it isn't a wait, it's a case that passed through
 * "falta documentación" for a few seconds while somebody moved it, and the line would read "0 min
 * esperando a terceros" — space spent to say nothing. Same rule as the dashboard's.
 */
export function waitingBreakdown(summary: ResolutionSummary): string {
  const total = summary.averageMinutes;
  const waiting = summary.averageWaitingMinutes;
  if (total === null || waiting === null || waiting < MINUTES_PER_HOUR) {
    return '';
  }
  return `${formatDuration(Math.max(total - waiting, 0))} de gestión · ${formatDuration(waiting)} esperando a terceros`;
}

/**
 * "45 min", "3 h 20 min", "2 d 5 h" — the same format the exported PDF uses. Rounded first, like
 * the PDF: the summary's average is a fraction, and without it read "3 h 20.333333 min".
 */
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

/**
 * La variación de expedientes resueltos contra el período anterior, sin veredicto: que se cierren
 * más o menos no es mejor ni peor, es el volumen del período.
 *
 * No se le aplica el piso de comparación que sí tienen las tasas: "2 más que el período anterior"
 * es exacto aunque el período anterior haya tenido tres expedientes. Lo que con base chica se
 * vuelve ruido es el porcentaje, no la resta.
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
 * La variación de la tasa de Fast Track, en puntos porcentuales y con veredicto: el Fast Track
 * agiliza, así que más es mejor.
 *
 * Se calla cuando el período anterior cerró pocos expedientes (ver {@link DEFAULT_MIN_COMPARISON_BASE}
 * en `core/util/trend`): con dos casos, pasar de uno a dos es "+50 pp" y eso no es una mejora, es
 * la aritmética de una base diminuta.
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

/**
 * La variación del tiempo promedio de resolución, con veredicto: tardar más es peor.
 *
 * La base de comparación son los DECIDIDOS del período anterior, la misma población que promedia
 * la tarjeta — no el total: un período con muchos caducados y pocas decisiones reales tiene un
 * promedio que un caso mueve fácil.
 */
export function resolutionTimeTrend(summary: ResolutionSummary, previous: ResolutionSummary): string {
  return trendText({
    current: summary.averageMinutes,
    previous: previous.averageMinutes,
    format: formatDuration,
    good: 'down',
    base: previous.decidedCases,
  });
}
