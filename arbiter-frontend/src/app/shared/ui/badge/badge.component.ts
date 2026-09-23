import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { StatusTone } from '../../../core/models/status-tone';

type Variant = 'solid' | 'strong' | 'dashed';

/**
 * `solid` for regular values, `strong` for a highlighted state (e.g. a final case status), `dashed`
 * for placeholders without data. `tone` adds a status dot; only the dot is colored, by design.
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
  `,
})
export class BadgeComponent {
  readonly variant = input<Variant>('solid');
  readonly tone = input<StatusTone>('neutral');
}
