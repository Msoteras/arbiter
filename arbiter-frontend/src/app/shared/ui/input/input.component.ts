import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

/**
 * Input de una línea del design system. Valor two-way vía model() → `[(value)]`.
 * Liviano a propósito: no implementa ControlValueAccessor (la app no usa Angular Forms).
 */
@Component({
  selector: 'app-input',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <input
      class="field"
      [type]="type()"
      [placeholder]="placeholder()"
      [value]="value()"
      [attr.min]="min()"
      (input)="value.set($any($event.target).value)"
    />
  `,
  styles: `
    :host { display: block; }
    .field {
      width: 100%;
      font: inherit;
      font-size: var(--font-size-body);
      padding: var(--space-2) var(--space-3);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: var(--surface);
      color: var(--text-primary);
    }
    .field:focus { outline: none; border-color: var(--action-secondary-border-hover); }
    .field::placeholder { color: var(--text-muted); }
  `,
})
export class InputComponent {
  readonly value = model('');
  readonly type = input<'text' | 'number' | 'email'>('text');
  readonly placeholder = input('');
  readonly min = input<number | null>(null);
}
