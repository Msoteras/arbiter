import { StatusTone } from './status-tone';

// Mirrors common-lib's DeadlinePriority: urgency against the art. 56 response deadline, computed
// by the backend.
export type DeadlinePriority = 'NONE' | 'WATCH' | 'URGENT' | 'CRITICAL' | 'OVERDUE';

// OVERDUE shares CRITICAL's tone and is told apart by its "Vencido" text.
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

/** Whitelist on purpose: a missing or unexpected value counts as not prioritized. */
export function isDeadlinePrioritized(priority: DeadlinePriority): boolean {
  return (
    priority === 'WATCH' ||
    priority === 'URGENT' ||
    priority === 'CRITICAL' ||
    priority === 'OVERDUE'
  );
}

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
