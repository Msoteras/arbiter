import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { StatusTone } from '../../../core/models/status-tone';

type Variant = 'solid' | 'strong' | 'dashed' | 'selected';

/**
 * `solid` for regular values, `strong` for a highlighted state (e.g. a final case status), `dashed`
 * for placeholders without data, `selected` for a yes in a grid of choices (a cause a coverage covers),
 * in the brand's selection colors. `tone` adds a status dot; only the dot is colored, by design.
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
      [class.selected]="variant() === 'selected'"
      [attr.data-tone]="tone() !== 'neutral' ? tone() : null"
      [class.busy]="busy()"
      [class.wrap]="wrap()"
      [class.has-dot]="tone() !== 'neutral'"
    >
      @if (tone() !== 'neutral') {
        <span class="dot-box" aria-hidden="true"><span class="dot"></span></span>
      }
      <span class="label"><ng-content /></span>
    </span>
  `,
  styles: `
    .badge {
      /* The dot centers on the first line, so a wrapped label keeps it next to its start. */
      display: inline-flex;
      align-items: flex-start;
      gap: var(--space-2);
      vertical-align: middle;
      font-size: var(--font-size-xs);
      font-weight: var(--badge-weight);
      padding: var(--badge-pad-y) var(--badge-pad-x);
      border-radius: var(--badge-radius);
      line-height: 1.4;
      white-space: nowrap;
    }
    /* The dot already marks the start, so that side needs less room than the text's end. */
    .badge.has-dot {
      padding-left: var(--badge-pad-x-dot);
    }
    .label {
      min-width: 0;
    }
    /* In a narrow table cell: the label breaks into lines and the pill becomes a rounded box. */
    .badge.wrap {
      white-space: normal;
      border-radius: var(--radius-ctl);
    }
    .badge.solid {
      border: 1px solid var(--badge-border);
      background: var(--badge-bg);
      color: var(--badge-fg);
    }
    .badge.strong {
      border: 1px solid var(--badge-strong-border);
      background: var(--badge-strong-bg);
      color: var(--text-primary);
      font-weight: var(--font-weight-medium);
    }
    .badge.selected {
      border: 1px solid var(--selected-border);
      background: var(--selected-bg);
      color: var(--accent-fg);
      font-weight: var(--font-weight-medium);
    }
    .badge.dashed {
      border: 1px dashed var(--border-strong);
      color: var(--text-muted);
    }

    .dot-box {
      flex-shrink: 0;
      display: inline-flex;
      align-items: center;
      height: calc(var(--font-size-xs) * 1.4);
      /* Centered on the line, the dot sits on the lowercase middle and reads low next to the
         capital every label starts with; this lifts it to the middle of the capitals. Not a
         transform: the busy pulse animates the dot's own. */
      position: relative;
      top: -0.07em;
    }
    .dot {
      width: 6px;
      height: 6px;
      border-radius: var(--radius-pill);
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
