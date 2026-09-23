import { Injectable, signal } from '@angular/core';

/**
 * In-memory flag so the full-viewport `app-loading` screen shows only on the first load after
 * login; later visits use inline loading. Reset on logout.
 */
@Injectable({ providedIn: 'root' })
export class AppReadyService {
  private readonly _ready = signal(false);
  readonly ready = this._ready.asReadonly();

  markReady(): void {
    if (!this._ready()) {
      this._ready.set(true);
    }
  }

  reset(): void {
    this._ready.set(false);
  }
}
