import { StatusTone } from './status-tone';

// Espejo del enum DeadlinePriority de common-lib
// (ar.edu.utn.frba.arbiter.common.enums.DeadlinePriority). Urgencia del expediente frente al
// plazo legal de respuesta (art. 56). Derivado en el back; acá solo se muestra.
export type DeadlinePriority = 'NONE' | 'WATCH' | 'URGENT' | 'CRITICAL' | 'OVERDUE';

// Semáforo: amarillo → rojo → rojo fuerte. NONE no pinta. OVERDUE comparte el danger de CRITICAL
// (no se agrega color nuevo al design system) y se distingue por el texto "Vencido".
const TONES: Record<DeadlinePriority, StatusTone> = {
  NONE: 'neutral',
  WATCH: 'warning',
  URGENT: 'risk',
  CRITICAL: 'danger',
  OVERDUE: 'danger',
};

export function deadlinePriorityTone(priority: DeadlinePriority): StatusTone {
  return TONES[priority];
}

/**
 * true cuando el expediente merece un chip de plazo. Whitelist a propósito: un backend que
 * todavía no manda `deadlinePriority` (o un valor inesperado) cuenta como "sin marca", no como
 * prioritario — así no se pinta un chip roto contra una respuesta vieja.
 */
export function isDeadlinePrioritized(priority: DeadlinePriority): boolean {
  return priority === 'WATCH' || priority === 'URGENT'
    || priority === 'CRITICAL' || priority === 'OVERDUE';
}

/**
 * Texto del chip: "Vencido" para OVERDUE, "Vence hoy/mañana" o "Vence en N días" para el resto.
 * `responseDeadline` es la fecha ISO (yyyy-MM-dd) que trae el back.
 */
export function deadlinePriorityLabel(
  priority: DeadlinePriority,
  responseDeadline: string,
): string {
  if (priority === 'NONE') {
    return '';
  }
  if (priority === 'OVERDUE') {
    return 'Vencido';
  }
  const days = daysUntil(responseDeadline);
  if (days <= 0) {
    return 'Vence hoy';
  }
  if (days === 1) {
    return 'Vence mañana';
  }
  return `Vence en ${days} días`;
}

function daysUntil(isoDate: string): number {
  const deadline = new Date(isoDate + 'T00:00:00');
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const msPerDay = 1000 * 60 * 60 * 24;
  return Math.round((deadline.getTime() - today.getTime()) / msPerDay);
}
