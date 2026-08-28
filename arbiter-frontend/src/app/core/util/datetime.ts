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

/** `2026-08-20` — una fecha sin hora, como las manda el backend desde una columna DATE. */
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Convierte a `Date` sin correr el día.
 *
 * `new Date('2026-08-20')` NO es medianoche local: el estándar obliga a interpretar las cadenas
 * de solo fecha como **UTC**, así que en Argentina (UTC−3) eso es el 19/08 a las 21:00. Con eso,
 * la fecha de una denuncia policial se mostraba un día antes del hecho que denunciaba — un
 * documento que parecía imposible cuando el dato guardado estaba bien.
 *
 * Las cadenas con hora (instantes ISO con zona) sí se parsean tal cual: ahí la conversión a hora
 * local es correcta y es lo que se quiere.
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
 * Date + time, 24h clock. Use for anything that shows an hour.
 *
 * <p>Con una fecha sin hora cae a solo fecha: inventar "00:00" para un dato que nunca tuvo hora
 * es afirmar una precisión que el documento no tiene.
 */
export function formatDateTime(value: string | Date | null | undefined, fallback = '—'): string {
  if (!value) {
    return fallback;
  }
  if (typeof value === 'string' && DATE_ONLY.test(value)) {
    return formatDate(value, fallback);
  }
  return parseLocal(value).toLocaleString('es-AR', DATE_TIME_OPTIONS);
}

/** Date only, no time. */
export function formatDate(value: string | Date | null | undefined, fallback = '—'): string {
  if (!value) {
    return fallback;
  }
  return parseLocal(value).toLocaleDateString('es-AR');
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
