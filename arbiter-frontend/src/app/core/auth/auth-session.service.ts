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
  /** Si el asegurado ya pasó el primer ingreso (H0009). Null para analista/referente, que no
      tienen onboarding. Lo lee `onboardingGuard` en cada navegación al portal. */
  onboardingComplete: boolean | null;
}

/**
 * Sesión de autenticación — token JWT en `sessionStorage`: sobrevive a un reload dentro de
 * la misma pestaña (así un F5 accidental no desloguea al asegurado a mitad de una denuncia),
 * pero se borra al cerrar la pestaña o el navegador — no es "recordarme" persistente.
 *
 * Ojo con el trade-off: `sessionStorage` es tan legible por un script inyectado como
 * `localStorage` (mismo riesgo de XSS); lo único que cambiaba con memoria-only era que el
 * token no sobrevivía a nada. Decisión explícita del equipo (14/8), no un descuido de
 * seguridad — si se revisita, es acá.
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

  /** Descarta lo guardado si ya venció — un token expirado no sirve de nada en memoria. */
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
