import { Injectable, signal } from '@angular/core';

/**
 * Stub de sesión del asegurado — mismo criterio que auth.interceptor.ts.
 *
 * Cuando se integre Auth0, la identidad del asegurado va a salir del claim del JWT
 * y este service pasa a leer el token (el formulario de identificación del portal
 * desaparece). Mientras tanto el asegurado se identifica una sola vez y queda en
 * localStorage. OJO: esto NO es autenticación ni manejo de credenciales propio
 * (decisión de arquitectura: Auth0) — es solo identificación para filtrar
 * los expedientes propios en el portal.
 */
@Injectable({ providedIn: 'root' })
export class InsuredSessionService {
  private static readonly STORAGE_KEY = 'arbiter.insuredId';

  private readonly _insuredId = signal<string | null>(
    localStorage.getItem(InsuredSessionService.STORAGE_KEY),
  );

  /** Identificación del asegurado en sesión, o null si todavía no se identificó. */
  readonly insuredId = this._insuredId.asReadonly();

  identify(insuredId: string): void {
    const trimmed = insuredId.trim();
    if (!trimmed) {
      return;
    }
    localStorage.setItem(InsuredSessionService.STORAGE_KEY, trimmed);
    this._insuredId.set(trimmed);
  }

  clear(): void {
    localStorage.removeItem(InsuredSessionService.STORAGE_KEY);
    this._insuredId.set(null);
  }
}
