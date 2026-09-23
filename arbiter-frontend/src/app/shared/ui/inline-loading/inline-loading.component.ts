import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { SpinnerComponent } from '../spinner/spinner.component';

/**
 * In-place loading indicator that does not cover the screen. `app-loading` is the full-viewport
 * overlay, reserved for the login → home startup.
 */
@Component({
  selector: 'app-inline-loading',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpinnerComponent],
  template: `
    <div class="inline-loading" role="status" aria-live="polite">
      <app-spinner [size]="size()" />
      @if (message()) {
        <span class="msg">{{ message() }}</span>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .inline-loading {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: var(--space-3);
      padding: var(--space-6) var(--space-4);
      color: var(--text-secondary);
    }
    .msg {
      font-size: var(--font-size-body);
      color: var(--text-secondary);
    }
  `,
})
export class InlineLoadingComponent {
  /** Spinner diameter in px. */
  readonly size = input(24);
  readonly message = input('');
}
