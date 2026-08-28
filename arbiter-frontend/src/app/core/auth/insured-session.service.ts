import { Injectable, computed, inject, signal } from '@angular/core';

import { AuthSessionService } from './auth-session.service';

/**
 * Identidad del asegurado para el portal. Sale del login (claim `insuredId` del JWT,
 * vía AuthSessionService): el asegurado ya está autenticado, no vuelve a tipear el DNI.
 *
 * El `identify` manual queda solo como FALLBACK para cuentas sin `insuredId` vinculado
 * (transitorio hasta que toda alta de asegurado quede ligada a su DNI). Cuando la sesión
 * trae insuredId, ese gana y el formulario de identificación del portal no aparece.
 */
@Injectable({ providedIn: 'root' })
export class InsuredSessionService {
  private static readonly STORAGE_KEY = 'arbiter.insuredId';

  private readonly authSession = inject(AuthSessionService);
  private readonly manualId = signal<string | null>(
    localStorage.getItem(InsuredSessionService.STORAGE_KEY),
  );

  /** DNI del asegurado: el del JWT si está, si no el identificado a mano. */
  readonly insuredId = computed(
    () => this.authSession.session()?.insuredId ?? this.manualId(),
  );

  identify(insuredId: string): void {
    const trimmed = insuredId.trim();
    if (!trimmed) {
      return;
    }
    localStorage.setItem(InsuredSessionService.STORAGE_KEY, trimmed);
    this.manualId.set(trimmed);
  }

  clear(): void {
    localStorage.removeItem(InsuredSessionService.STORAGE_KEY);
    this.manualId.set(null);
  }
}
