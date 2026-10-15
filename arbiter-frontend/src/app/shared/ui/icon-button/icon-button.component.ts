import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type IconButtonIcon = 'edit' | 'delete' | 'confirm';

/**
 * Square button that shows only an icon, for a row's own actions (edit, remove) where a text button
 * per row would crowd the list. `label` is required: it is the accessible name and the tooltip,
 * the only words the action has. The icons are a closed set on purpose, so the same action always
 * looks the same.
 */
@Component({
  selector: 'app-icon-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class.is-disabled]': 'disabled()',
  },
  template: `
    <button
      type="button"
      class="icon-btn"
      [class.danger]="icon() === 'delete'"
      [attr.aria-label]="label()"
      [attr.title]="label()"
      [disabled]="disabled()"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
        @switch (icon()) {
          @case ('edit') {
            <path d="M4 20h4L19 9l-4-4L4 16v4z" stroke-width="1.8" stroke-linejoin="round" />
          }
          @case ('delete') {
            <path
              d="M5 7h14M10 7V5h4v2M6 7l1 13h10l1-13"
              stroke-width="1.8"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          }
          @case ('confirm') {
            <path
              d="M5 13l4 4L19 7"
              stroke-width="2.2"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          }
        }
      </svg>
    </button>
  `,
  styles: `
    @use 'media';

    :host {
      display: inline-block;
    }
    /* Same reason as app-button: the consumer's (click) sits on the host and would still fire. */
    :host(.is-disabled) {
      pointer-events: none;
    }
    .icon-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: var(--icon-btn);
      height: var(--icon-btn);
      padding: 0;
      background: none;
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      color: var(--accent-fg);
      cursor: pointer;
    }
    @include media.touch {
      .icon-btn {
        width: var(--touch-target);
        height: var(--touch-target);
      }
    }
    .icon-btn:hover:not(:disabled) {
      border-color: var(--selected-border);
      background: var(--selected-bg);
    }
    /* Destructive but sober: colored icon and hover border, no red fill. */
    .icon-btn.danger {
      color: var(--status-danger);
    }
    .icon-btn.danger:hover:not(:disabled) {
      border-color: var(--status-danger);
      background: none;
    }
    .icon-btn:focus-visible {
      outline: none;
      border-color: var(--border-focus);
      box-shadow: var(--focus-ring);
    }
    .icon-btn:disabled {
      opacity: 0.5;
      cursor: default;
    }
    .icon-btn svg {
      width: var(--icon-md);
      height: var(--icon-md);
    }
  `,
})
export class IconButtonComponent {
  readonly icon = input.required<IconButtonIcon>();
  readonly label = input.required<string>();
  readonly disabled = input(false);
}
