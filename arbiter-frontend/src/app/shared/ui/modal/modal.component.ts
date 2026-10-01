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
  host: { '(document:keydown.escape)': 'onEscape()' },
  template: `
    @if (open()) {
      <!-- eslint-disable-next-line @angular-eslint/template/click-events-have-key-events, @angular-eslint/template/interactive-supports-focus -- Escape closes it from the keyboard. -->
      <div class="backdrop" [class.side]="variant() === 'side'" (click)="onBackdrop($event)">
        <div
          class="modal"
          [class.side]="variant() === 'side'"
          [class.lg]="size() === 'lg'"
          [class.no-actions]="hideActions()"
          role="dialog"
          aria-modal="true"
        >
          <div class="modal-head">
            @if (heading()) {
              <h2 class="modal-title">{{ heading() }}</h2>
            }
            <button type="button" class="modal-close" aria-label="Cerrar" (click)="close.emit()">
              ✕
            </button>
          </div>
          <div class="modal-body"><ng-content /></div>
          @if (!hideActions()) {
            <div class="modal-actions"><ng-content select="[modalActions]" /></div>
          }
        </div>
      </div>
    }
  `,
  styles: `
    @use 'media';

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
      --modal-pad: var(--space-5);
      display: flex;
      flex-direction: column;
      background: var(--surface);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-modal);
      box-shadow: var(--shadow-modal);
      width: 100%;
      max-width: 440px;
      max-height: 90vh;
      /* dvh tracks the mobile browser's collapsing toolbars; 90vh stays as the fallback. */
      max-height: calc(100dvh - 2 * var(--space-4));
      overflow: hidden;
      animation: modal-in var(--dur-3) var(--ease-out);
    }
    .modal-body {
      flex: 1 1 auto;
      min-height: 0;
      overflow-y: auto;
      overscroll-behavior: contain;
      /* The top padding leaves room for the focus ring of a field at the very top. */
      padding: var(--space-1) var(--modal-pad) 0;
    }
    /* A spacer, not padding: padding would leave a gap under a sticky footer in the body. */
    .modal.no-actions .modal-body::after {
      content: '';
      display: block;
      height: var(--modal-pad);
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
      max-height: none;
      border-radius: 0;
      border-width: 0 0 0 1px;
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
      flex: none;
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--space-3);
      padding: var(--modal-pad) var(--modal-pad) var(--space-1);
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
      flex: none;
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: var(--space-3);
      padding: var(--space-4) var(--modal-pad) var(--modal-pad);
    }

    @include media.touch {
      .modal-close {
        display: flex;
        align-items: center;
        justify-content: center;
        min-width: var(--touch-target);
        min-height: var(--touch-target);
        margin: calc(var(--space-3) * -1);
      }
    }
    @include media.phone {
      .backdrop {
        padding: var(--space-3);
      }
      .modal {
        --modal-pad: var(--space-4);
        max-height: calc(100dvh - 2 * var(--space-3));
      }
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

  protected onBackdrop(event: MouseEvent): void {
    if (this.dismissable() && event.target === event.currentTarget) {
      this.close.emit();
    }
  }

  protected onEscape(): void {
    if (this.open()) {
      this.close.emit();
    }
  }
}
