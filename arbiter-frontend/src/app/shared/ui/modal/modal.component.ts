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
      <div class="backdrop" [class.side]="variant() === 'side'" (click)="onBackdrop()">
        <div
          class="modal"
          [class.side]="variant() === 'side'"
          [class.lg]="size() === 'lg'"
          role="dialog"
          aria-modal="true"
          (click)="$event.stopPropagation()"
        >
          @if (heading()) {
            <h2 class="modal-title">{{ heading() }}</h2>
          }
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
      /* Un contenido alto (ej. el wizard de denuncia) scrollea dentro del diálogo en vez de
         empujar la página o desbordar el viewport. */
      max-height: 90vh;
      overflow-y: auto;
    }
    /* Diálogo ancho para formularios (ej. nueva denuncia). */
    .modal.lg {
      max-width: 680px;
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
      from {
        transform: translateX(100%);
      }
      to {
        transform: translateX(0);
      }
    }
    .modal-title {
      margin: 0 0 var(--space-2);
      font-size: var(--font-size-lg);
      font-weight: var(--font-weight-medium);
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
  /** Ancho del diálogo centrado: `md` (440, default) o `lg` (680, para formularios). */
  readonly size = input<'md' | 'lg'>('md');
  /** Oculta la barra de acciones del pie: para cuerpos que traen su propia botonera (ej. el wizard). */
  readonly hideActions = input(false);
  /**
   * Si el click en el fondo cierra el diálogo. `true` por defecto (diálogos livianos, ej.
   * notificaciones). Poner en `false` para formularios donde un click accidental afuera haría
   * perder lo cargado (ej. el wizard de nueva denuncia): ahí solo se cierra desde sus botones.
   */
  readonly dismissable = input(true);
  readonly close = output<void>();

  protected onBackdrop(): void {
    if (this.dismissable()) {
      this.close.emit();
    }
  }
}
