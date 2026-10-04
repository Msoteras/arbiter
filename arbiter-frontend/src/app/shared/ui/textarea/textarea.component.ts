import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';

@Component({
  selector: 'app-textarea',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <textarea
      class="field"
      [id]="resolvedId()"
      [rows]="rows()"
      [placeholder]="placeholder()"
      [disabled]="disabled()"
      [value]="value()"
      (input)="value.set($any($event.target).value)"
    ></textarea>
  `,
  styles: `
    @use 'media';

    :host {
      display: block;
    }
    .field {
      width: 100%;
      font: inherit;
      /* Below 16px iOS would zoom on focus; core/util/ios-focus-zoom.ts turns that off. */
      font-size: var(--font-size-md);
      padding: var(--space-2) var(--space-3);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: var(--surface);
      color: var(--text-primary);
      resize: vertical;
    }
    @include media.desktop {
      .field {
        font-size: var(--font-size-body);
      }
    }
    .field:focus {
      outline: none;
      border-color: var(--border-focus);
      box-shadow: var(--focus-ring);
    }
    .field::placeholder {
      color: var(--text-muted);
    }
    .field:disabled {
      color: var(--text-muted);
      cursor: default;
    }
  `,
})
export class TextareaComponent {
  private static autoIdCounter = 0;
  private readonly autoId = `app-textarea-${TextareaComponent.autoIdCounter++}`;

  readonly value = model('');
  readonly rows = input(4);
  readonly placeholder = input('');
  readonly id = input<string | null>(null);
  readonly disabled = input(false);

  protected readonly resolvedId = computed(() => this.id() ?? this.autoId);
}
