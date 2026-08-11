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

/**
 * Saludo según la hora del día, para el encabezado de las pantallas de inicio.
 * Cortes pensados para el uso local (es-AR): mañana 6–13, tarde 13–20, resto noche.
 * Recibe la fecha por parámetro (default: ahora) para poder testearlo sin mockear el reloj.
 */
export function saludoSegunHora(now: Date = new Date()): string {
  const hora = now.getHours();
  if (hora >= 6 && hora < 13) {
    return 'Buenos días';
  }
  if (hora >= 13 && hora < 20) {
    return 'Buenas tardes';
  }
  return 'Buenas noches';
}

/**
 * Fecha larga con día de la semana para el subtítulo del saludo, ej. "Martes 10 de agosto".
 * Capitaliza la inicial porque `toLocaleDateString('es-AR')` devuelve el día en minúscula.
 */
export function fechaLarga(now: Date = new Date()): string {
  const texto = now.toLocaleDateString('es-AR', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  });
  return texto.charAt(0).toUpperCase() + texto.slice(1);
}
