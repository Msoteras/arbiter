import { StatusTone } from './status-tone';

// Mirrors the UserStatus enum in common-lib (ar.edu.utn.frba.arbiter.common.enums.UserStatus).
// INACTIVE is reserved for when account deactivation gets built — no backend flow produces it
// yet, PENDING is the only real "needs action" status today.
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
