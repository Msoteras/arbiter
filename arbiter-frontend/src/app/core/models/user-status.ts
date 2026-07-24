import { StatusTone } from './status-tone';

// Espejo del enum UserStatus de common-lib (ar.edu.utn.frba.arbiter.common.enums.UserStatus).
// INACTIVE queda reservado para cuando se construya deshabilitar cuentas — hoy ningún flujo
// del backend lo produce, PENDING es el único estado "de acción" real.
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
