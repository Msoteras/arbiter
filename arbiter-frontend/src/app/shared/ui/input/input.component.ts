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
      [attr.autocomplete]="autocomplete()"
      (input)="value.set($any($event.target).value)"
    />
  `,
  styles: `
    :host { display: block; }
    .field {
      width: 100%;
      font: inherit;
      /* 16px en mobile evita el zoom de iOS Safari al enfocar; 13px desde sm hacia arriba. */
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
    .field::placeholder { color: var(--text-muted); }
  `,
})
export class InputComponent {
  readonly value = model('');
  readonly type = input<'text' | 'number' | 'email' | 'date' | 'password'>('text');
  readonly placeholder = input('');
  readonly min = input<number | null>(null);
  readonly autocomplete = input<string | null>(null);
}
