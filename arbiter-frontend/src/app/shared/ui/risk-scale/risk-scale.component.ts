import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { RISK_BANDS, RiskBand, riskBandLabel } from '../../../core/models/risk-band';

/** Each band's share of the scale, low to critical: the same cuts as app-fraud-gauge. */
const BAND_WIDTHS = [30, 30, 20, 20];

/**
 * The fraud risk thermometer: a low-to-critical gradient with a marker in the middle of the case's
 * band. With `labels`, the four bands are named under it and the case's band is the one in bold.
 * Decorative for screen readers: the score and band are always written next to it.
 *
 * The marker goes by band, not by score: each insurer sets its own cuts, so a 33 can be High and
 * would otherwise sit on the Medium color. The score places it only when there is no band.
 */
@Component({
  selector: 'app-risk-scale',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bar" aria-hidden="true">
      <span class="marker" [style.left.%]="position()"></span>
    </div>
    @if (labels()) {
      <div class="bands" aria-hidden="true">
        @for (b of bands; track b.band) {
          <span [class.on]="b.band === band()" [style.flex-basis.%]="b.width">{{ b.label }}</span>
        }
      </div>
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--space-2);
    }
    .bar {
      position: relative;
      height: var(--space-2);
      border-radius: var(--radius-pill);
      background: linear-gradient(
        90deg,
        var(--status-ok) 0%,
        var(--status-warning) 45%,
        var(--status-risk) 70%,
        var(--status-danger) 100%
      );
    }
    .marker {
      position: absolute;
      top: calc(var(--space-1) * -1);
      width: var(--space-1);
      height: var(--space-4);
      border-radius: var(--radius-pill);
      background: var(--text-primary);
      box-shadow: 0 0 0 calc(var(--space-1) / 2) var(--surface);
      transform: translateX(-50%);
      animation: marker-in var(--dur-3) var(--ease-out);
    }
    @keyframes marker-in {
      from {
        left: 0;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .marker {
        animation: none;
      }
    }
    .bands {
      display: flex;
      font-size: var(--font-size-sm);
      line-height: 1.2;
      color: var(--text-muted);
    }
    .bands span {
      min-width: 0;
      padding-left: var(--space-1);
      border-left: 1px solid var(--border-default);
      white-space: nowrap;
    }
    .bands span.on {
      font-weight: var(--font-weight-bold);
      color: var(--text-primary);
    }
  `,
})
export class RiskScaleComponent {
  /** 0–100. */
  readonly score = input.required<number>();
  readonly band = input<RiskBand | null>(null);
  /** Names the four bands under the bar. */
  readonly labels = input(false);

  protected readonly position = computed(() => {
    const band = this.band();
    if (band === null) {
      return Math.min(100, Math.max(0, this.score()));
    }
    const i = RISK_BANDS.indexOf(band);
    const start = BAND_WIDTHS.slice(0, i).reduce((sum, width) => sum + width, 0);
    return start + BAND_WIDTHS[i] / 2;
  });
  protected readonly bands = RISK_BANDS.map((band, i) => ({
    band,
    label: riskBandLabel(band),
    width: BAND_WIDTHS[i],
  }));
}
