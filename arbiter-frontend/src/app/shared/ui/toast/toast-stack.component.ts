import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { ToastService } from './toast.service';

/**
 * Se monta una sola vez, en `app.html`, y dibuja lo que haya en `ToastService`. Fondo neutro +
 * borde de color por tono (semáforo `--status-*`), nunca un fondo saturado — mismo criterio que
 * `app-badge`/`app-fraud-gauge` para no gastar el acento de estado.
 */
@Component({
  selector: 'app-toast-stack',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toast-stack" aria-live="assertive" aria-atomic="true">
      @for (t of service.toasts(); track t.id) {
        <div class="toast" [class]="'tone-' + t.tone" role="alert">
          <span class="msg">{{ t.message }}</span>
          <button type="button" class="dismiss" (click)="service.dismiss(t.id)" aria-label="Cerrar aviso">
            <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
            </svg>
          </button>
        </div>
      }
    </div>
  `,
  styles: `
    .toast-stack {
      position: fixed;
      z-index: 200;
      bottom: var(--space-5);
      right: var(--space-5);
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      max-width: min(360px, calc(100vw - 2 * var(--space-5)));
    }
    .toast {
      display: flex;
      align-items: flex-start;
      gap: var(--space-3);
      padding: var(--space-3) var(--space-4);
      border-radius: var(--radius-ctl);
      border: 1px solid var(--border-default);
      border-left: 3px solid var(--status-info);
      background: var(--surface-head);
      box-shadow: var(--shadow-pop);
    }
    .toast.tone-ok { border-left-color: var(--status-ok); }
    .toast.tone-warning { border-left-color: var(--status-warning); }
    .toast.tone-danger { border-left-color: var(--status-danger); }
    .msg {
      flex: 1 1 auto;
      color: var(--text-primary);
      font-size: var(--font-size-sm);
      line-height: 1.4;
    }
    .dismiss {
      flex-shrink: 0;
      display: inline-flex;
      padding: 0;
      border: none;
      background: none;
      color: var(--text-muted);
      cursor: pointer;
    }
    .dismiss:hover { color: var(--text-secondary); }
    .dismiss:focus-visible { outline: none; box-shadow: var(--focus-ring); border-radius: var(--radius-ctl); }
    .dismiss svg { width: 14px; height: 14px; }
  `,
})
export class ToastStackComponent {
  protected readonly service = inject(ToastService);
}
