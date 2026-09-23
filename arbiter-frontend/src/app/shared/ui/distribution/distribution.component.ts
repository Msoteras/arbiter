import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { StatusTone } from '../../../core/models/status-tone';
import { RatePipe } from '../../pipes/rate.pipe';

export interface DistributionItem {
  label: string;
  count: number;
  tone: StatusTone;
  /** Tooltip opcional, para categorías cuyo nombre solo no alcanza a explicar qué es. */
  description?: string;
}

export type DistributionShape = 'bar' | 'ring';

/**
 * Stacked bar or ring plus a legend with exact counts and shares. Plain divs and SVG, no chart
 * library: a canvas would be heavier and less accessible.
 */
@Component({
  selector: 'app-distribution',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="dist" [class.with-ring]="shape() === 'ring' && total() === null">
      @if (total() === null) {
        @if (shape() === 'ring') {
          <!-- r = 15.915 gives a circumference of 100, so dash values are percentages; the
               offset of 25 starts the ring at 12 o'clock. -->
          <div class="ring-wrap">
            <svg class="ring" viewBox="0 0 42 42" role="img" [attr.aria-label]="summary()">
              <circle class="ring-track" cx="21" cy="21" r="15.915" />
              @for (slice of slices(); track slice.label) {
                <circle
                  class="ring-arc tone-{{ slice.tone }}"
                  cx="21"
                  cy="21"
                  r="15.915"
                  [style.opacity]="slice.weight"
                  [attr.stroke-dasharray]="slice.share * 100 + ' ' + (100 - slice.share * 100)"
                  [attr.stroke-dashoffset]="25 - slice.offset * 100"
                />
              }
            </svg>
            <!-- HTML rather than SVG <text>, so it uses the type scale instead of viewBox units. -->
            <span class="ring-center" aria-hidden="true">
              <span class="ring-total tabular">{{ sum() }}</span>
              @if (centerLabel()) {
                <span class="ring-caption">{{ centerLabel() }}</span>
              }
            </span>
          </div>
        } @else {
          <div class="stack" role="img" [attr.aria-label]="summary()">
            @for (slice of slices(); track slice.label) {
              <span
                class="slice tone-{{ slice.tone }}"
                [style.width.%]="slice.share * 100"
                [class.faded]="slice.faded"
              ></span>
            }
          </div>
        }
      }

      <ul class="legend">
        @for (slice of slices(); track slice.label) {
          <li [title]="slice.description ?? null">
            <span
              class="dot tone-{{ slice.tone }}"
              [style.opacity]="shape() === 'ring' ? slice.weight : null"
              [class.faded]="shape() === 'bar' && slice.faded"
              aria-hidden="true"
            ></span>
            <span class="name">{{ slice.label }}</span>
            <span class="count tabular">{{ slice.count }}</span>
            <span class="share tabular">{{ slice.share | rate }}</span>
          </li>
        }
      </ul>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .dist.with-ring {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-5);
    }
    .dist.with-ring .legend {
      flex: 1 1 var(--ring-size);
    }
    .stack {
      display: flex;
      width: 100%;
      height: var(--space-2);
      border-radius: var(--radius-pill);
      overflow: hidden;
      background: var(--surface-sunken);
    }
    .slice {
      display: block;
      height: 100%;
    }
    .stack + .legend {
      margin-top: var(--space-4);
    }
    /* Repeated tones are faded rather than given a color the design system does not have. */
    .slice.faded,
    .dot.faded {
      opacity: 0.5;
    }

    .ring-wrap {
      position: relative;
      flex: 0 0 auto;
      width: var(--ring-size);
      height: var(--ring-size);
    }
    .ring {
      width: 100%;
      height: 100%;
    }
    .ring-track,
    .ring-arc {
      fill: none;
      stroke-width: 5;
    }
    .ring-track {
      stroke: var(--surface-sunken);
    }
    .ring-center {
      position: absolute;
      inset: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--space-1);
    }
    .ring-total {
      font-size: var(--font-size-2xl);
      font-weight: var(--font-weight-bold);
      line-height: 1;
      letter-spacing: -0.02em;
      color: var(--text-primary);
    }
    .ring-caption {
      font-size: var(--font-size-2xs);
      font-weight: var(--font-weight-medium);
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--text-muted);
    }

    .legend {
      margin: 0;
      padding: 0;
      list-style: none;
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .legend li {
      display: grid;
      grid-template-columns: auto 1fr auto auto;
      align-items: center;
      gap: var(--space-2);
      font-size: var(--font-size-body);
      color: var(--text-secondary);
    }
    .dot {
      width: var(--space-2);
      height: var(--space-2);
      border-radius: var(--radius-pill);
    }
    .name {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .count {
      font-weight: var(--font-weight-bold);
      color: var(--text-primary);
    }
    .share {
      min-width: 3.5ch;
      text-align: right;
      color: var(--text-muted);
    }

    .tone-ok {
      background: var(--status-ok);
    }
    .tone-warning {
      background: var(--status-warning);
    }
    .tone-risk {
      background: var(--status-risk);
    }
    .tone-danger {
      background: var(--status-danger);
    }
    .tone-info {
      background: var(--status-info);
    }
    .tone-neutral {
      background: var(--text-tertiary);
    }
    .ring-arc {
      background: none;
    }
    .ring-arc.tone-ok {
      stroke: var(--status-ok);
    }
    .ring-arc.tone-warning {
      stroke: var(--status-warning);
    }
    .ring-arc.tone-risk {
      stroke: var(--status-risk);
    }
    .ring-arc.tone-danger {
      stroke: var(--status-danger);
    }
    .ring-arc.tone-info {
      stroke: var(--status-info);
    }
    .ring-arc.tone-neutral {
      stroke: var(--text-primary);
    }
  `,
  imports: [RatePipe],
})
export class DistributionComponent {
  readonly items = input.required<DistributionItem[]>();
  readonly shape = input<DistributionShape>('bar');
  /** Caption under the ring's center total. */
  readonly centerLabel = input('');
  /**
   * Population for overlapping categories (one case in two buckets). When set, no bar or ring is
   * drawn, since overlapping slices cannot add up to a whole.
   */
  readonly total = input<number | null>(null);

  protected readonly sum = computed(() => this.items().reduce((sum, item) => sum + item.count, 0));

  protected readonly slices = computed(() => {
    const total = this.total() ?? this.sum();
    const seen = new Map<StatusTone, number>();
    let offset = 0;
    return this.items().map((item) => {
      const repeat = seen.get(item.tone) ?? 0;
      seen.set(item.tone, repeat + 1);
      const share = total === 0 ? 0 : item.count / total;
      const slice = {
        ...item,
        share,
        offset,
        faded: repeat > 0,
        // Intensity ramp for slices sharing a tone (all of them when the dimension carries no
        // status, e.g. claim causes), instead of inventing a palette.
        weight: Math.max(1 - repeat * 0.22, 0.25),
      };
      offset += share;
      return slice;
    });
  });

  protected readonly summary = computed(() =>
    this.items()
      .map((item) => `${item.label}: ${item.count}`)
      .join('. '),
  );
}
