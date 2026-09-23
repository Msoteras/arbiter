import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { SpinnerComponent } from '../spinner/spinner.component';

/** Full-viewport loading overlay that covers the sidebar, topbar and content. */
@Component({
  selector: 'app-loading',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpinnerComponent],
  template: `
    <div class="loading" role="status" aria-live="polite">
      <app-spinner [size]="72" />
      <div class="loading-text">
        <p class="msg">
          {{ message()
          }}<span class="dots" aria-hidden="true"><span>.</span><span>.</span><span>.</span></span>
        </p>
        @if (sub()) {
          <p class="sub">{{ sub() }}</p>
        }
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    /* fixed so it covers everything even when rendered inside a single screen. */
    .loading {
      position: fixed;
      inset: 0;
      z-index: 200;
      background: var(--surface-sunken);
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--space-5);
      color: var(--text-primary);
      padding: var(--space-6);
      text-align: center;
    }
    .loading-text {
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
      align-items: center;
    }
    .msg {
      font-size: var(--font-size-md);
      font-weight: var(--font-weight-medium);
      color: var(--text-secondary);
    }
    .sub {
      font-size: var(--font-size-sm);
      color: var(--text-muted);
    }
    .dots span {
      animation: arb-dots 1.2s ease-in-out infinite;
    }
    .dots span:nth-child(2) {
      animation-delay: 0.18s;
    }
    .dots span:nth-child(3) {
      animation-delay: 0.36s;
    }
    @keyframes arb-dots {
      0%,
      100% {
        opacity: 0.25;
      }
      50% {
        opacity: 1;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .dots span {
        animation: none;
        opacity: 0.6;
      }
    }
  `,
})
export class LoadingComponent {
  readonly message = input('Cargando');
  readonly sub = input('');
}
