/**
 * `toLocaleString('es-AR')` without `hour12` can drop the AM/PM marker on some ICU builds, showing
 * 19:30 as "07:30"; forcing `hour12: false` keeps a 24h clock everywhere.
 */

const DATE_TIME_OPTIONS: Intl.DateTimeFormatOptions = {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
};

/** `2026-08-20`: a date without time, as the backend sends a DATE column. */
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * `new Date('2026-08-20')` is parsed as UTC midnight, i.e. the previous day in Argentina (UTC−3),
 * so date-only strings are built as local dates. Strings with time are parsed as-is.
 */
function parseLocal(value: string | Date): Date {
  if (value instanceof Date) {
    return value;
  }
  const dateOnly = DATE_ONLY.exec(value);
  if (!dateOnly) {
    return new Date(value);
  }
  const [year, month, day] = value.split('-').map(Number);
  return new Date(year, month - 1, day);
}

/**
 * Whether the date is fully typed (four-digit year). `<input type="date">` emits on every key, so
 * typing 2026 passes through valid-looking `0002-…`, `0020-…`, `0202-…` values.
 */
export function isTypedDate(value: string): boolean {
  return DATE_ONLY.test(value) && value.slice(0, 4) >= '1000';
}

/**
 * Whether the declared police report predates the event. Times only decide on the same date, as
 * the backend does. Returns `false` on incomplete data: doubt is not an inconsistency.
 */
export function isPoliceReportBeforeEvent(
  eventDate: string,
  eventTime: string,
  policeDate: string,
  policeTime: string,
): boolean {
  if (!isTypedDate(eventDate) || !isTypedDate(policeDate)) {
    return false;
  }
  if (policeDate !== eventDate) {
    return policeDate < eventDate;
  }
  return !!eventTime && !!policeTime && policeTime < eventTime;
}

/** Date + time, 24h clock. A date-only value falls back to date only instead of inventing "00:00". */
export function formatDateTime(value: string | Date | null | undefined, fallback = '—'): string {
  if (!value) {
    return fallback;
  }
  if (typeof value === 'string' && DATE_ONLY.test(value)) {
    return formatDate(value, fallback);
  }
  return parseLocal(value).toLocaleString('es-AR', DATE_TIME_OPTIONS);
}

export function formatDate(value: string | Date | null | undefined, fallback = '—'): string {
  if (!value) {
    return fallback;
  }
  return parseLocal(value).toLocaleDateString('es-AR');
}

export function formatTime(value: string | Date): string {
  return parseLocal(value).toLocaleTimeString('es-AR', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

/** Chat-style day marker: "Hoy", "Ayer" or "jue 25 sept". */
export function chatDayLabel(value: string | Date, now: Date = new Date()): string {
  const date = parseLocal(value);
  const startOf = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const days = Math.round((startOf(now) - startOf(date)) / 86_400_000);
  if (days === 0) return 'Hoy';
  if (days === 1) return 'Ayer';
  return date
    .toLocaleDateString('es-AR', { weekday: 'short', day: 'numeric', month: 'short' })
    .replace(',', '');
}

/** List-row stamp: the time for today's messages, the date for older ones. */
export function chatListStamp(value: string | Date, now: Date = new Date()): string {
  return chatDayLabel(value, now) === 'Hoy'
    ? formatTime(value)
    : parseLocal(value).toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit' });
}

/** Greeting by time of day: morning 6–13, afternoon 13–20, night otherwise. */
export function greetingForTimeOfDay(now: Date = new Date()): string {
  const hour = now.getHours();
  if (hour >= 6 && hour < 13) {
    return 'Buenos días';
  }
  if (hour >= 13 && hour < 20) {
    return 'Buenas tardes';
  }
  return 'Buenas noches';
}

/** e.g. "Martes 10 de agosto"; capitalized because es-AR returns the weekday in lowercase. */
export function longDate(now: Date = new Date()): string {
  const text = now.toLocaleDateString('es-AR', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  });
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * Today as `yyyy-MM-dd` in LOCAL time. `toISOString()` is UTC: from 21:00 in Argentina (UTC−3)
 * it already returns tomorrow.
 */
export function todayIso(now: Date = new Date()): string {
  const month = `${now.getMonth() + 1}`.padStart(2, '0');
  const day = `${now.getDate()}`.padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * Shifts a `yyyy-MM-dd` date by whole days. In UTC on purpose: a local `Date` crossing a DST boundary
 * lands on 23:00 of the previous day. Returns '' for an incomplete date, so a half-typed input never
 * yields a real-looking result.
 */
export function addDays(isoDate: string, days: number): string {
  if (!isTypedDate(isoDate)) {
    return '';
  }
  const shifted = new Date(`${isoDate}T00:00:00Z`);
  shifted.setUTCDate(shifted.getUTCDate() + days);
  return shifted.toISOString().slice(0, 10);
}

/** Timeline axis label: "14/06" for day or week buckets, "jun 2026" for months. */
export function bucketLabel(bucket: string, granularity: 'DAY' | 'WEEK' | 'MONTH'): string {
  // Without the time part it would be parsed as UTC and shift back a day in UTC−3.
  const date = new Date(`${bucket}T00:00:00`);
  return granularity === 'MONTH'
    ? date.toLocaleDateString('es-AR', { month: 'short', year: 'numeric' })
    : date.toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit' });
}
