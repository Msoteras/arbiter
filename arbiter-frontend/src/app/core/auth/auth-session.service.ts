import { Injectable, computed, signal } from '@angular/core';

import { UserRole } from '../models/user-role';

export interface AuthSession {
  token: string;
  expiresAt: string;
  /** Id del usuario logueado. Es contra este id que se resuelve "míos" en la bandeja del
      analista y el atajo "Tomar" (auto-asignarse un expediente). */
  id: number;
  email: string;
  rol: UserRole;
  nombre: string;
  apellido: string;
  /** DNI del asegurado (null para analista/referente). Sale del login; el portal lo usa
      sin volver a pedirlo. */
  insuredId: string | null;
}

/**
 * Sesión de autenticación — token JWT en memoria (nunca localStorage: se pierde al
 * recargar la página, tal como pide el criterio de aceptación de H0001). Es a propósito,
 * no un placeholder: Auth0 ya está integrado (ver CLAUDE.md, decisión #8).
 */
@Injectable({ providedIn: 'root' })
export class AuthSessionService {
  private readonly _session = signal<AuthSession | null>(null);

  readonly session = this._session.asReadonly();
  readonly token = computed(() => this._session()?.token ?? null);

  start(session: AuthSession): void {
    this._session.set(session);
  }

  clear(): void {
    this._session.set(null);
  }
}
