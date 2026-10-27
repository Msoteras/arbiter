import { ChangeDetectionStrategy, Component, computed, input, model, output } from '@angular/core';

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
      (keydown.enter)="onEnter($event)"
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
      box-shadow: var(--shadow-field);
      transition:
        border-color var(--dur-1) ease,
        box-shadow var(--dur-2) var(--ease-standard);
      resize: vertical;
    }
    @include media.desktop {
      .field {
        font-size: var(--font-size-body);
      }
    }
    .field:hover:not(:focus):not([readonly]):not(:disabled) {
      border-color: var(--border-strong);
    }
    .field:focus {
      outline: none;
      border-color: var(--border-focus);
      box-shadow: var(--focus-ring), var(--shadow-field);
    }
    .field::placeholder {
      color: var(--text-muted);
    }
    .field:disabled {
      cursor: default;
      opacity: 0.55;
      resize: none;
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
  /** Enter emits `enter` instead of breaking the line; Shift+Enter still breaks it. For chats. */
  readonly submitOnEnter = input(false);
  readonly enter = output<void>();

  protected readonly resolvedId = computed(() => this.id() ?? this.autoId);

  protected onEnter(event: Event): void {
    const key = event as KeyboardEvent;
    // isComposing: Enter also confirms an IME composition (accents on some keyboards).
    if (!this.submitOnEnter() || key.shiftKey || key.isComposing) {
      return;
    }
    event.preventDefault();
    this.enter.emit();
  }
}
