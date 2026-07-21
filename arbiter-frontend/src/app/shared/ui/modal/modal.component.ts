import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/**
 * Diálogo modal con backdrop. `open` controla la visibilidad; emite `close` al
 * clickear el fondo. Cuerpo como contenido proyectado; botonera en el slot
 * [modalActions]. `variant`: `center` (default, diálogo centrado) o `side`
 * (panel deslizante desde el borde derecho, alto completo — para formularios
 * tipo "alta de X" sobre un listado, patrón del wireframe):
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
      <div class="backdrop" [class.side]="variant() === 'side'" (click)="close.emit()">
        <div
          class="modal"
          [class.side]="variant() === 'side'"
          role="dialog"
          aria-modal="true"
          (click)="$event.stopPropagation()"
        >
          @if (heading()) { <h2 class="modal-title">{{ heading() }}</h2> }
          <ng-content />
          <div class="modal-actions"><ng-content select="[modalActions]" /></div>
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
    }
    .backdrop.side { justify-content: flex-end; padding: 0; }
    .modal {
      background: var(--surface);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-modal);
      box-shadow: var(--shadow-modal);
      padding: var(--space-5);
      width: 100%;
      max-width: 440px;
    }
    .modal.side {
      max-width: 420px;
      height: 100%;
      border-radius: 0;
      border-width: 0 0 0 1px;
      overflow-y: auto;
      animation: slide-in 0.18s ease-out;
    }
    @keyframes slide-in {
      from { transform: translateX(100%); }
      to { transform: translateX(0); }
    }
    .modal-title { margin: 0 0 var(--space-2); font-size: var(--font-size-lg); font-weight: var(--font-weight-medium); }
    .modal-actions { display: flex; justify-content: flex-end; gap: var(--space-3); margin-top: var(--space-4); }
  `,
})
export class ModalComponent {
  readonly open = input(false);
  readonly heading = input('');
  readonly variant = input<'center' | 'side'>('center');
  readonly close = output<void>();
}
