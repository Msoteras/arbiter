import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

/**
 * Unlike app-select, this keeps the native control: `accent-color` paints it, and the native input
 * brings keyboard focus, the indeterminate state and screen-reader support for free.
 */
@Component({
  selector: 'app-checkbox',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <label class="cb">
      <input
        type="checkbox"
        [checked]="checked()"
        [disabled]="disabled()"
        (change)="onToggle($event)"
      />
      <span class="cb-label"><ng-content /></span>
    </label>
  `,
  styles: `
    :host {
      display: block;
    }

    .cb {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      /* The 18px box alone is too small a touch target. */
      min-height: 40px;
      font-size: var(--font-size-body);
      color: var(--text-primary);
      cursor: pointer;
    }

    .cb:has(input:disabled) {
      cursor: default;
      opacity: 0.55;
    }

    input[type='checkbox'] {
      width: 18px;
      height: 18px;
      flex-shrink: 0;
      /* --accent-strong, not --accent: the browser picks the tick color by contrast, and on the
         light brand teal it picks a dark tick that reads poorly. */
      accent-color: var(--accent-strong);
      cursor: inherit;
    }

    .cb-label {
      text-wrap: pretty;
    }
  `,
})
export class CheckboxComponent {
  readonly checked = model(false);
  readonly disabled = input(false);

  protected onToggle(event: Event): void {
    this.checked.set((event.target as HTMLInputElement).checked);
  }
}
