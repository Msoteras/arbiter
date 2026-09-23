import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Each instance needs its own mask id, or the second one would override the first. */
let uid = 0;

/**
 * Deliberate differences from the SVG export in public/brand/: strokes use `currentColor` so one
 * component works on light and dark backgrounds, and the wordmark is HTML text so it uses the app
 * font (--font-sans) instead of the export's Helvetica/Arial.
 */
@Component({
  selector: 'app-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="logo" [class.symbol-only]="variant() === 'symbol'">
      <svg
        class="mark"
        viewBox="0 0 88 88"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        aria-hidden="true"
      >
        <defs>
          <mask [attr.id]="maskId()">
            <rect x="-10" y="-10" width="108" height="108" fill="white" />
            <circle cx="21" cy="67" r="13.5" fill="black" />
          </mask>
        </defs>
        <path
          d="M 55 12 A 34 34 0 1 0 76 33"
          stroke="currentColor"
          stroke-width="4.5"
          stroke-linecap="butt"
          [attr.mask]="maskRef()"
        />
        <path
          d="M 60.0 14.0 A 34 34 0 0 1 73.4 27.0 M 70.0 9.8 L 65.2 29.2 M 77.2 16.8 L 58.0 22.3"
          stroke="currentColor"
          stroke-width="4"
          fill="none"
          stroke-linecap="butt"
        />
        <circle cx="21" cy="67" r="9" stroke="currentColor" stroke-width="4.5" fill="none" />
        <circle cx="21" cy="67" r="3" fill="currentColor" />
      </svg>

      @if (variant() === 'lockup') {
        <span class="wordmark">Arbiter</span>
      }
    </span>
  `,
  styles: `
    :host {
      display: inline-flex;
    }

    .logo {
      display: inline-flex;
      align-items: center;
      gap: 0.42em;
      color: inherit;
      /* Everything scales with font-size. */
      font-size: var(--logo-size, var(--font-size-lg));
    }

    .mark {
      width: 1.15em;
      height: 1.15em;
      flex-shrink: 0;
      /* Optical alignment: the symbol's weight sits bottom-left, below the text baseline. */
      margin-block-start: -0.06em;
    }

    .wordmark {
      font-family: var(--font-sans);
      font-weight: var(--font-weight-medium);
      font-size: 1em;
      letter-spacing: -0.011em;
      line-height: 1;
      white-space: nowrap;
    }

    .symbol-only .mark {
      width: 1.3em;
      height: 1.3em;
    }
  `,
})
export class LogoComponent {
  /** `lockup` = symbol + wordmark; `symbol` = symbol only (narrow spaces). */
  readonly variant = input<'lockup' | 'symbol'>('lockup');

  private readonly id = `arbiter-logo-gap-${uid++}`;
  protected readonly maskId = computed(() => this.id);
  protected readonly maskRef = computed(() => `url(#${this.id})`);
}
