import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';

/**
 * Editor de una lista de textos (reglas de negocio, exclusiones, criterios de Fast Track): agrega,
 * edita y quita items. Emite la lista completa en cada cambio; el padre la persiste en su draft.
 * Local al feature de reglas — no es una primitiva del kit.
 *
 * Las filas se muestran en modo lectura y se editan de a una con el lápiz. Antes todas eran un
 * input siempre abierto: una lista de seis reglas se veía como un formulario de seis campos vacíos
 * de contexto, y no se distinguía lo que estaba escrito de lo que faltaba escribir.
 *
 * El estado vacío tiene dos formas. Con `emptyTitle` es el estado vacío completo (ícono, para qué
 * sirve la lista, un ejemplo y el botón): para una sección que ES la lista, donde no hay nada más
 * que mirar. Sin él queda la línea suelta de `emptyText`, que es lo que corresponde cuando la lista
 * es una parte chica de una pantalla más grande (los criterios del Fast Track).
 */
@Component({
  selector: 'app-string-list-editor',
  imports: [ButtonComponent, InputComponent, BadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (items().length === 0 && emptyTitle()) {
      <div class="empty-box">
        <span class="empty-icon" aria-hidden="true">
          <ng-content select="[emptyIcon]" />
        </span>
        <p class="empty-title">{{ emptyTitle() }}</p>
        @if (emptyHint()) {
          <p class="empty-hint">{{ emptyHint() }}</p>
        }
        <app-button (click)="add()">{{ emptyCta() || addLabel() }}</app-button>
      </div>
    } @else {
      <ul class="items">
        @for (item of items(); track $index) {
          <li class="item" [class.editing]="editing() === $index">
            @if (editing() === $index) {
              <app-input
                class="grow"
                [value]="item"
                (valueChange)="update($index, $event)"
                [placeholder]="placeholder()"
              />
              <app-button variant="secondary" size="sm" (click)="stopEditing()">Listo</app-button>
            } @else {
              <div class="text">
                <p class="item-text">{{ item || placeholder() }}</p>
                @if (badge()) {
                  <app-badge variant="solid">{{ badge() }}</app-badge>
                }
              </div>
              <div class="row-actions">
                <button
                  type="button"
                  class="icon-btn"
                  [attr.aria-label]="'Editar'"
                  (click)="startEditing($index)"
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                    <path d="M4 20h4L19 9l-4-4L4 16v4z" stroke-linejoin="round" />
                  </svg>
                </button>
                <button
                  type="button"
                  class="icon-btn danger"
                  [attr.aria-label]="'Quitar'"
                  (click)="remove($index)"
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                    <path d="M5 7h14M10 7V5h4v2M6 7l1 13h10l1-13" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </button>
              </div>
            }
          </li>
        } @empty {
          <li class="empty t-note">{{ emptyText() }}</li>
        }
      </ul>
      <app-button variant="secondary" size="sm" (click)="add()">{{ addLabel() }}</app-button>
    }
  `,
  styles: `
    :host { display: block; }
    .items { list-style: none; margin: 0 0 var(--space-2); padding: 0; display: flex; flex-direction: column; }
    .item {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      padding: var(--space-3) 0;
    }
    .item + .item { border-top: 1px solid var(--border-subtle); }
    .item.editing { gap: var(--space-2); }
    .grow { flex: 1 1 auto; }
    .text { flex: 1 1 auto; min-width: 0; display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
    .item-text { margin: 0; color: var(--text-primary); }
    .empty { margin: 0 0 var(--space-1); padding: var(--space-2) 0; }

    /* Las acciones aparecen con el hover o el foco, pero nunca se esconden del todo del teclado:
       con :focus-within siguen siendo alcanzables tabulando. */
    .row-actions { flex: 0 0 auto; display: flex; gap: var(--space-1); opacity: 0.55; transition: opacity var(--dur-1) ease; }
    .item:hover .row-actions, .item:focus-within .row-actions { opacity: 1; }
    .icon-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 32px;
      height: 32px;
      padding: 0;
      background: none;
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      color: var(--text-tertiary);
      cursor: pointer;
    }
    .icon-btn:hover { border-color: var(--border-strong); color: var(--text-secondary); }
    .icon-btn.danger:hover { border-color: var(--status-danger); color: var(--status-danger); }
    .icon-btn:focus-visible { outline: none; border-color: var(--border-focus); box-shadow: var(--focus-ring); }
    .icon-btn svg { width: 16px; height: 16px; }

    /* Estado vacío: borde punteado para que se lea como un lugar por llenar y no como una card
       con contenido. */
    .empty-box {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: var(--space-2);
      padding: var(--space-6) var(--space-4);
      border: 1px dashed var(--border-strong);
      border-radius: var(--radius-card);
      background: var(--surface-soft);
      text-align: center;
    }
    .empty-icon { color: var(--text-tertiary); }
    .empty-title { margin: 0; font-weight: var(--font-weight-medium); color: var(--text-primary); }
    /* Con medida: el ejemplo es la parte que se lee de verdad y a todo el ancho de la card cuesta. */
    .empty-hint { margin: 0; max-width: 46ch; color: var(--text-secondary); font-size: var(--font-size-sm); }

    @media (prefers-reduced-motion: reduce) {
      .row-actions { transition: none; }
    }
  `,
})
export class StringListEditorComponent {
  readonly items = input<string[]>([]);
  readonly placeholder = input('');
  readonly addLabel = input('+ Agregar');
  /** Línea suelta cuando la lista está vacía y NO se usa el estado vacío completo. */
  readonly emptyText = input('');
  /** Con título, la lista vacía muestra el estado vacío completo en vez de la línea suelta. */
  readonly emptyTitle = input('');
  /** Para qué sirve la lista, con un ejemplo: es lo que destraba a alguien que la ve por primera vez. */
  readonly emptyHint = input('');
  /** Texto del botón del estado vacío ("+ Agregar primera regla"). Por defecto, el de `addLabel`. */
  readonly emptyCta = input('');
  /** Etiqueta igual para toda la lista: quién usa estos textos (el motor, el analista, el modelo). */
  readonly badge = input('');

  readonly itemsChange = output<string[]>();

  /** Índice de la fila en edición; null = todas en lectura. Una por vez, como el acordeón. */
  protected readonly editing = signal<number | null>(null);

  protected startEditing(index: number): void {
    this.editing.set(index);
  }

  protected stopEditing(): void {
    this.editing.set(null);
  }

  protected add(): void {
    const next = [...this.items(), ''];
    // La fila nueva nace en edición: agregar y que aparezca un renglón vacío que hay que descubrir
    // cómo se escribe es exactamente el paso que sobra.
    this.editing.set(next.length - 1);
    this.itemsChange.emit(next);
  }

  protected update(index: number, value: string): void {
    this.itemsChange.emit(this.items().map((item, i) => (i === index ? value : item)));
  }

  protected remove(index: number): void {
    this.editing.set(null);
    this.itemsChange.emit(this.items().filter((_, i) => i !== index));
  }
}
