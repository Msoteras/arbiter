import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

/** Área de texto multilínea. Mismo estilo de campo que app-input. */
@Component({
  selector: 'app-textarea',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <textarea
      class="field"
      [rows]="rows()"
      [placeholder]="placeholder()"
      [value]="value()"
      (input)="value.set($any($event.target).value)"
    ></textarea>
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
      resize: vertical;
    }
    .field:focus { outline: none; border-color: var(--action-secondary-border-hover); }
    .field::placeholder { color: var(--text-muted); }
  `,
})
export class TextareaComponent {
  readonly value = model('');
  readonly rows = input(4);
  readonly placeholder = input('');
}
