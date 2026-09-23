import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Each instance needs its own mask id, or the second one would override the first. */
let uid = 0;

/** Strokes use `currentColor`, so the spinner adapts to dark buttons and light surfaces alike. */
@Component({
  selector: 'app-spinner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      class="mark"
      [attr.width]="size()"
      [attr.height]="size()"
      viewBox="0 0 88 88"
      fill="none"
      role="img"
      aria-label="Cargando"
    >
      <defs>
        <mask [attr.id]="maskId">
          <rect x="-10" y="-10" width="108" height="108" fill="white" />
          <circle cx="21" cy="67" r="13.5" fill="black" />
        </mask>
      </defs>
      <g class="ring">
        <path
          d="M 55 12 A 34 34 0 1 0 76 33"
          stroke="currentColor"
          stroke-width="4.5"
          stroke-linecap="butt"
          [attr.mask]="maskRef"
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
      </g>
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      line-height: 0;
    }
    .ring {
      transform-origin: 44px 44px;
      animation: arb-spin 1.15s linear infinite;
    }
    @keyframes arb-spin {
      to {
        transform: rotate(360deg);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .ring {
        animation: none;
      }
    }
  `,
})
export class SpinnerComponent {
  /** Side length in px. */
  readonly size = input(20);

  private readonly id = `arb-spinner-gap-${uid++}`;
  protected readonly maskId = this.id;
  protected readonly maskRef = `url(#${this.id})`;
}
