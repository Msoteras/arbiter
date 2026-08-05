/**
 * Centralized date/time formatting for the SPA.
 *
 * `toLocaleString('es-AR')` without an explicit `hour12` can drop the AM/PM
 * marker on some ICU builds, rendering 19:30 as "07:30" (a 12h difference with
 * no meridiem). Forcing `hour12: false` keeps a 24h clock everywhere, which is
 * what the event time of a claim needs (it can matter for the analysis).
 */

const DATE_TIME_OPTIONS: Intl.DateTimeFormatOptions = {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
};

/** Date + time, 24h clock. Use for anything that shows an hour. */
export function formatDateTime(value: string | Date | null | undefined, fallback = '—'): string {
  if (!value) {
    return fallback;
  }
  return new Date(value).toLocaleString('es-AR', DATE_TIME_OPTIONS);
}

/** Date only, no time. */
export function formatDate(value: string | Date | null | undefined, fallback = '—'): string {
  if (!value) {
    return fallback;
  }
  return new Date(value).toLocaleDateString('es-AR');
}
