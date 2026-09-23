import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

export interface ChipOption {
  value: string;
  label: string;
}

/**
 * Single-select group for a few options (two to five) that should all be visible at once.
 * `allowDeselect` is off by default (radio semantics); enable it where an empty answer is valid.
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
    :host {
      display: block;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
    }
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
    .chip:hover:not(:disabled) {
      border-color: var(--action-secondary-border-hover);
    }
    .chip:focus-visible {
      outline: 2px solid var(--focus-ring);
      outline-offset: 2px;
    }
    .chip.active {
      background: var(--selected-bg);
      border-color: var(--selected-border);
      color: var(--accent-fg);
      font-weight: var(--font-weight-medium);
    }
    .chip:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
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
