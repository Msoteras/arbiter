import { Injectable, inject, signal } from '@angular/core';
import { NavigationStart, Router } from '@angular/router';
import { filter } from 'rxjs';

/** Opened from several places (top bar, home card, claims list); the modal itself is hosted by the app shell. */
@Injectable({ providedIn: 'root' })
export class NewClaimModalService {
  private readonly _open = signal(false);
  readonly isOpen = this._open.asReadonly();

  /**
   * Opening the modal does not change the URL, so browser "back" would navigate underneath it.
   * Close on NavigationStart (not End) so the dialog is gone before the new screen renders.
   */
  constructor() {
    inject(Router)
      .events.pipe(filter((event) => event instanceof NavigationStart))
      .subscribe(() => this.close());
  }

  open(): void {
    this._open.set(true);
  }

  close(): void {
    this._open.set(false);
  }
}
