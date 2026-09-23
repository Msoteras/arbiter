import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

/**
 * For on/off settings with immediate effect (a rule, a scoring factor). The accessible name goes in
 * `ariaLabel` because the visible label is usually a sibling element, not content of the control.
 */
@Component({
  selector: 'app-switch',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      class="track"
      role="switch"
      [attr.aria-checked]="checked()"
      [attr.aria-label]="ariaLabel()"
      [disabled]="disabled()"
      (click)="toggle()"
    >
      <span class="knob" aria-hidden="true"></span>
    </button>
  `,
  styles: `
    :host {
      display: inline-block;
    }
    .track {
      display: block;
      width: 40px;
      height: 22px;
      padding: 2px;
      border: 1px solid transparent;
      border-radius: var(--radius-pill);
      background: var(--border-control);
      cursor: pointer;
      transition: background-color var(--dur-1) ease;
    }
    .knob {
      display: block;
      width: 16px;
      height: 16px;
      border-radius: var(--radius-pill);
      background: var(--text-on-emphasis);
      box-shadow: var(--shadow-card);
      transition: transform var(--dur-1) ease;
    }
    /* --accent-fill, not the strong teal: that one is tuned for white text on solid buttons and
       looks dull on a toggle. */
    .track[aria-checked='true'] {
      background: var(--accent-fill);
      border-color: var(--accent-fill-border);
    }
    .track[aria-checked='true'] .knob {
      transform: translateX(18px);
    }
    .track:disabled {
      cursor: default;
      opacity: 0.55;
    }
    .track:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
    }

    @media (prefers-reduced-motion: reduce) {
      .track,
      .knob {
        transition: none;
      }
    }
  `,
})
export class SwitchComponent {
  readonly checked = model(false);
  readonly disabled = input(false);
  readonly ariaLabel = input<string | null>(null);

  protected toggle(): void {
    if (!this.disabled()) {
      this.checked.set(!this.checked());
    }
  }
}
