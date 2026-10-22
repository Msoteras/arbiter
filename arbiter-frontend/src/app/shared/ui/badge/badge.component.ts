import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { StatusTone } from '../../../core/models/status-tone';

type Variant = 'solid' | 'strong' | 'dashed';

/**
 * `solid` for regular values, `strong` for a highlighted state (e.g. a final case status), `dashed`
 * for placeholders without data. `tone` adds a status dot; only the dot is colored, by design.
 * `busy` makes the dot pulse: the backend is working on that state right now (a classification).
 */
@Component({
  selector: 'app-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="badge"
      [class.solid]="variant() === 'solid'"
      [class.strong]="variant() === 'strong'"
      [class.dashed]="variant() === 'dashed'"
      [attr.data-tone]="tone() !== 'neutral' ? tone() : null"
      [class.busy]="busy()"
      [class.wrap]="wrap()"
    >
      @if (tone() !== 'neutral') {
        <span class="dot" aria-hidden="true"></span>
      }
      <ng-content />
    </span>
  `,
  styles: `
    .badge {
      display: inline-block;
      font-size: var(--font-size-xs);
      padding: var(--space-1) var(--space-3);
      border-radius: var(--radius-pill);
      line-height: 1.4;
      white-space: nowrap;
    }
    /* In a narrow table cell: the label breaks into lines and the pill becomes a rounded box. */
    .badge.wrap {
      white-space: normal;
      border-radius: var(--radius-ctl);
    }
    .badge.solid {
      border: 1px solid var(--border-control);
      background: var(--surface-head);
      color: var(--text-secondary);
    }
    .badge.strong {
      border: 1px solid var(--text-tertiary);
      background: var(--surface-head);
      color: var(--text-primary);
      font-weight: var(--font-weight-medium);
    }
    .badge.dashed {
      border: 1px dashed var(--border-strong);
      color: var(--text-muted);
    }

    .dot {
      display: inline-block;
      width: 6px;
      height: 6px;
      margin-right: var(--space-2);
      border-radius: var(--radius-pill);
      vertical-align: middle;
    }
    .badge[data-tone='ok'] .dot {
      background: var(--status-ok);
    }
    .badge[data-tone='warning'] .dot {
      background: var(--status-warning);
    }
    .badge[data-tone='risk'] .dot {
      background: var(--status-risk);
    }
    .badge[data-tone='danger'] .dot {
      background: var(--status-danger);
    }
    .badge[data-tone='info'] .dot {
      background: var(--status-info);
    }
    .badge.busy .dot {
      animation: dot-pulse 1.6s var(--ease-standard) infinite;
    }
    @keyframes dot-pulse {
      0%,
      100% {
        opacity: 1;
        transform: scale(1);
      }
      50% {
        opacity: 0.35;
        transform: scale(0.7);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .badge.busy .dot {
        animation: none;
      }
    }
  `,
})
export class BadgeComponent {
  readonly variant = input<Variant>('solid');
  readonly tone = input<StatusTone>('neutral');
  readonly busy = input(false);
  readonly wrap = input(false);
}
