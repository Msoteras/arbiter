import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/**
 * `variant="side"` is a full-height panel sliding in from the right, for create forms over a list.
 *
 *   <app-modal [open]="x()" heading="Título" variant="side" (close)="cancel()">
 *     <p>cuerpo…</p>
 *     <ng-container modalActions>
 *       <app-button variant="secondary" (click)="cancel()">Cancelar</app-button>
 *       <app-button (click)="ok()">Confirmar</app-button>
 *     </ng-container>
 *   </app-modal>
 */
@Component({
  selector: 'app-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (open()) {
      <div class="backdrop" [class.side]="variant() === 'side'" (click)="onBackdrop()">
        <div
          class="modal"
          [class.side]="variant() === 'side'"
          [class.lg]="size() === 'lg'"
          role="dialog"
          aria-modal="true"
          (click)="$event.stopPropagation()"
        >
          <div class="modal-head">
            @if (heading()) {
              <h2 class="modal-title">{{ heading() }}</h2>
            }
            <button type="button" class="modal-close" aria-label="Cerrar" (click)="close.emit()">
              ✕
            </button>
          </div>
          <ng-content />
          @if (!hideActions()) {
            <div class="modal-actions"><ng-content select="[modalActions]" /></div>
          }
        </div>
      </div>
    }
  `,
  styles: `
    .backdrop {
      position: fixed;
      inset: 0;
      background: var(--overlay-backdrop);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 100;
      padding: var(--space-4);
      animation: backdrop-in var(--dur-2) ease-out;
    }
    @keyframes backdrop-in {
      from {
        opacity: 0;
      }
      to {
        opacity: 1;
      }
    }
    .backdrop.side {
      justify-content: flex-end;
      padding: 0;
    }
    .modal {
      background: var(--surface);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-modal);
      box-shadow: var(--shadow-modal);
      padding: var(--space-5);
      width: 100%;
      max-width: 440px;
      max-height: 90vh;
      overflow-y: auto;
      animation: modal-in var(--dur-3) var(--ease-out);
    }
    @keyframes modal-in {
      from {
        opacity: 0;
        transform: translateY(10px) scale(0.98);
      }
      to {
        opacity: 1;
        transform: none;
      }
    }
    .modal.lg {
      max-width: 680px;
    }
    .modal.side {
      max-width: 420px;
      height: 100%;
      border-radius: 0;
      border-width: 0 0 0 1px;
      overflow-y: auto;
      animation: slide-in var(--dur-2) var(--ease-out);
    }
    @keyframes slide-in {
      from {
        transform: translateX(100%);
      }
      to {
        transform: translateX(0);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .backdrop,
      .modal,
      .modal.side {
        animation: none;
      }
    }
    .modal-head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--space-3);
      margin-bottom: var(--space-2);
    }
    .modal-title {
      margin: 0;
      font-size: var(--font-size-lg);
      font-weight: var(--font-weight-medium);
    }
    .modal-close {
      flex-shrink: 0;
      border: none;
      background: none;
      cursor: pointer;
      color: var(--text-muted);
      font-size: var(--font-size-sm);
      line-height: 1;
      padding: var(--space-1);
      margin: calc(var(--space-1) * -1);
      border-radius: var(--radius-ctl);
    }
    .modal-close:hover {
      color: var(--text-primary);
    }
    .modal-close:focus-visible {
      outline: 2px solid var(--border-focus);
      outline-offset: 2px;
    }
    .modal-actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-3);
      margin-top: var(--space-4);
    }
  `,
})
export class ModalComponent {
  readonly open = input(false);
  readonly heading = input('');
  readonly variant = input<'center' | 'side'>('center');
  readonly size = input<'md' | 'lg'>('md');
  /** For bodies that bring their own action buttons. */
  readonly hideActions = input(false);
  /**
   * Whether a backdrop click closes the dialog. Set `false` for forms where a stray click would lose
   * the input; the header close button always closes.
   */
  readonly dismissable = input(true);
  readonly close = output<void>();

  protected onBackdrop(): void {
    if (this.dismissable()) {
      this.close.emit();
    }
  }
}
