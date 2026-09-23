import { StatusTone } from './status-tone';

// Mirrors common-lib's UserStatus enum. No backend flow produces INACTIVE yet.
export type UserStatus = 'ACTIVE' | 'PENDING' | 'INACTIVE';

const LABELS: Record<UserStatus, string> = {
  ACTIVE: 'Activo',
  PENDING: 'Pendiente',
  INACTIVE: 'Inactivo',
};

export function userStatusLabel(value: string): string {
  return (LABELS as Record<string, string>)[value] ?? value;
}

const TONES: Record<UserStatus, StatusTone> = {
  ACTIVE: 'ok',
  PENDING: 'warning',
  INACTIVE: 'neutral',
};

export function userStatusTone(value: string): StatusTone {
  return (TONES as Record<string, StatusTone>)[value] ?? 'neutral';
}
