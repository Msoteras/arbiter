import { Injectable, computed, signal } from '@angular/core';

import { UserRole } from '../models/user-role';

export interface AuthSession {
  token: string;
  expiresAt: string;
  email: string;
  rol: UserRole;
  nombre: string;
  apellido: string;
}

/**
 * Sesión de autenticación — token JWT en memoria (nunca localStorage: se pierde al
 * recargar la página, tal como pide el criterio de aceptación de H0001). Paso
 * transitorio hasta integrar Auth0 (ver CLAUDE.md, decisión #8).
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
