import { Injectable, computed, inject, signal } from '@angular/core';

import { AuthSessionService } from './auth-session.service';

/**
 * The insured's identity comes from the JWT `insuredId` claim; manual `identify` is only a
 * fallback for accounts without one.
 */
@Injectable({ providedIn: 'root' })
export class InsuredSessionService {
  private static readonly STORAGE_KEY = 'arbiter.insuredId';

  private readonly authSession = inject(AuthSessionService);
  private readonly manualId = signal<string | null>(
    localStorage.getItem(InsuredSessionService.STORAGE_KEY),
  );

  readonly insuredId = computed(() => this.authSession.session()?.insuredId ?? this.manualId());

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
