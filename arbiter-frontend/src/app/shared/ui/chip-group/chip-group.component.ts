import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

/** Una opción del grupo. `value` es lo que queda en el modelo; `label` lo que se lee. */
export interface ChipOption {
  value: string;
  label: string;
}

/**
 * Grupo de chips de selección única del design system.
 *
 * Va donde las opciones son POCAS (dos a cinco) y conviene verlas todas de una: elegir entre
 * ellas es la tarea, no un detalle escondido. Con más opciones que eso está `app-select`, que las
 * guarda detrás de un clic; para encender algo puntual, `app-switch`; para marcar VARIOS de un
 * conjunto no sirve —es selección única—, ahí va `app-checkbox`.
 *
 * <p>Dos tamaños porque los dos usos que tiene hoy no pesan lo mismo. `md` (default) es una
 * elección principal de la pantalla: el "¿Qué te pasó?" del alta de denuncia, que en mobile tiene
 * que ser un target cómodo. `sm` es un control secundario que acompaña a otro campo, como el atajo
 * de franja horaria debajo de la hora — ahí competir en peso con el campo que acelera sería peor.
 *
 * <p>`allowDeselect` está apagado por default, que es la semántica de un grupo de radios: una vez
 * elegida una opción hay una elegida. Se prende donde vaciar es una respuesta válida (la franja
 * horaria, que es opcional).
 *
 * <p>Accesibilidad: es un `radiogroup` real, no una fila de botones sueltos. Cada chip lleva
 * `role="radio"` con su `aria-checked`, así el lector de pantalla anuncia "1 de 4" y las flechas
 * se comportan como en cualquier grupo de radios. El estado seleccionado se distingue por borde,
 * fondo y peso tipográfico además del color, para no depender de ver el teal.
 */
@Component({
  selector: 'app-chip-group',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="chips" role="radiogroup" [attr.aria-label]="ariaLabel()">
      @for (option of options(); track option.value) {
        <button
          type="button"
          class="chip"
          role="radio"
          [class.sm]="size() === 'sm'"
          [class.active]="value() === option.value"
          [attr.aria-checked]="value() === option.value"
          [disabled]="disabled()"
          (click)="select(option.value)"
        >
          {{ option.label }}
        </button>
      }
    </div>
  `,
  styles: `
    :host { display: block; }
    .chips { display: flex; flex-wrap: wrap; gap: var(--space-2); }
    .chip {
      font: inherit;
      font-size: var(--font-size-md);
      padding: var(--space-2) var(--space-5);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-pill);
      background: var(--surface);
      color: var(--text-secondary);
      cursor: pointer;
      transition: all 0.15s;
    }
    .chip.sm {
      font-size: var(--font-size-sm);
      padding: var(--space-2) var(--space-3);
    }
    .chip:hover:not(:disabled) { border-color: var(--action-secondary-border-hover); }
    .chip:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
    .chip.active {
      background: var(--selected-bg);
      border-color: var(--selected-border);
      color: var(--accent-fg);
      font-weight: var(--font-weight-medium);
    }
    .chip:disabled { opacity: 0.5; cursor: not-allowed; }
  `,
})
export class ChipGroupComponent {
  readonly options = input<readonly ChipOption[]>([]);
  readonly value = model('');
  readonly ariaLabel = input<string | null>(null);
  readonly disabled = input(false);
  readonly size = input<'sm' | 'md'>('md');
  readonly allowDeselect = input(false);

  select(next: string): void {
    this.value.set(this.allowDeselect() && this.value() === next ? '' : next);
  }
}
