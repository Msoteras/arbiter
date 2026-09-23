import { Injectable, computed, signal } from '@angular/core';

import { UserRole } from '../models/user-role';

export interface AuthSession {
  token: string;
  expiresAt: string;
  id: number;
  email: string;
  rol: UserRole;
  nombre: string;
  apellido: string;
  /** The insured's DNI; null for other roles. */
  insuredId: string | null;
  /** Null for roles without onboarding. */
  onboardingComplete: boolean | null;
}

/**
 * JWT kept in `sessionStorage`: survives a reload in the same tab but not closing it. Deliberate
 * trade-off: it is as exposed to XSS as `localStorage`.
 */
@Injectable({ providedIn: 'root' })
export class AuthSessionService {
  private static readonly STORAGE_KEY = 'arbiter.session';

  private readonly _session = signal<AuthSession | null>(AuthSessionService.restore());

  readonly session = this._session.asReadonly();
  readonly token = computed(() => this._session()?.token ?? null);

  start(session: AuthSession): void {
    this._session.set(session);
    sessionStorage.setItem(AuthSessionService.STORAGE_KEY, JSON.stringify(session));
  }

  clear(): void {
    this._session.set(null);
    sessionStorage.removeItem(AuthSessionService.STORAGE_KEY);
  }

  /** Drops the stored session if it already expired. */
  private static restore(): AuthSession | null {
    const raw = sessionStorage.getItem(AuthSessionService.STORAGE_KEY);
    if (!raw) {
      return null;
    }
    try {
      const session = JSON.parse(raw) as AuthSession;
      if (new Date(session.expiresAt).getTime() <= Date.now()) {
        sessionStorage.removeItem(AuthSessionService.STORAGE_KEY);
        return null;
      }
      return session;
    } catch {
      sessionStorage.removeItem(AuthSessionService.STORAGE_KEY);
      return null;
    }
  }
}
