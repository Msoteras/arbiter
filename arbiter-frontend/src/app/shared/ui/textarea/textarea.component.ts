import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';

/** Área de texto multilínea. Mismo estilo de campo que app-input. */
@Component({
  selector: 'app-textarea',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <textarea
      class="field"
      [id]="resolvedId()"
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
      /* 16px en mobile evita el zoom de iOS Safari al enfocar; 13px desde sm hacia arriba. */
      font-size: var(--font-size-lg);
      padding: var(--space-2) var(--space-3);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: var(--surface);
      color: var(--text-primary);
      resize: vertical;
    }
    @media (min-width: 640px) {
      .field { font-size: var(--font-size-body); }
    }
    .field:focus { outline: none; border-color: var(--border-focus); box-shadow: var(--focus-ring); }
    .field::placeholder { color: var(--text-muted); }
  `,
})
export class TextareaComponent {
  private static autoIdCounter = 0;
  private readonly autoId = `app-textarea-${TextareaComponent.autoIdCounter++}`;

  readonly value = model('');
  readonly rows = input(4);
  readonly placeholder = input('');
  readonly id = input<string | null>(null);

  protected readonly resolvedId = computed(() => this.id() ?? this.autoId);
}
