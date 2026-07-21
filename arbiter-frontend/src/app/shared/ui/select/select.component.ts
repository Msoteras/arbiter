import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

export interface SelectOption {
  value: string;
  label: string;
}

/**
 * Select de una línea del design system, mismo tratamiento visual que app-input.
 * Valor two-way vía model() → `[(value)]` (o `[value]` + `(valueChange)` cuando el
 * consumidor necesita interceptar el cambio, ej. para resetear paginación).
 * `placeholder` es la opción vacía (ej. "Todos los estados") — omitila si el campo es obligatorio.
 */
@Component({
  selector: 'app-select',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <select class="field" [value]="value()" (change)="value.set($any($event.target).value)">
      @if (placeholder()) {
        <option value="">{{ placeholder() }}</option>
      }
      @for (opt of options(); track opt.value) {
        <option [value]="opt.value">{{ opt.label }}</option>
      }
    </select>
  `,
  styles: `
    :host { display: block; }
    .field {
      width: 100%;
      font: inherit;
      font-size: var(--font-size-lg);
      padding: var(--space-2) var(--space-3);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: var(--surface);
      color: var(--text-primary);
    }
    @media (min-width: 640px) {
      .field { font-size: var(--font-size-body); }
    }
    .field:focus { outline: none; border-color: var(--action-secondary-border-hover); }
  `,
})
export class SelectComponent {
  readonly value = model('');
  readonly options = input<SelectOption[]>([]);
  readonly placeholder = input('');
}
