import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { IconButtonComponent } from '../icon-button/icon-button.component';

/**
 * One rule written as a sentence: words and controls side by side, inside a block. The words and
 * fields are projected; `[blockAside]` goes to the right (a link to where a locked rule is set) and
 * `[blockError]` under the block.
 *
 * `locked` is a rule that applies here but is edited on another screen: it shows a lock and takes
 * no input. `removable` adds the "remove" action, for a list where the referent picks which rules
 * apply; a fixed catalog leaves it off. `missing` marks an active rule without its value.
 */
@Component({
  selector: 'app-rule-block',
  imports: [IconButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="block" [class.locked]="locked()" [class.missing]="missing()" [class.off]="off()">
      @if (locked()) {
        <svg class="lock" viewBox="0 0 24 24" aria-hidden="true">
          <rect x="5" y="11" width="14" height="10" rx="2" />
          <path d="M8 11V8a4 4 0 0 1 8 0v3" />
        </svg>
      }
      <div class="phrase"><ng-content /></div>
      <ng-content select="[blockAside]" />
      @if (removable()) {
        <app-icon-button
          icon="delete"
          size="sm"
          [label]="removeLabel()"
          [disabled]="disabled()"
          (click)="remove.emit()"
        />
      }
    </div>
    <ng-content select="[blockError]" />
  `,
  styles: `
    @use 'media';

    :host {
      display: block;
    }
    .block {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      min-height: var(--touch-target);
      padding: var(--space-2) var(--space-3);
      border: 1px solid var(--border-default);
      border-radius: var(--radius-ctl);
      background: var(--surface);
    }
    .phrase {
      flex: 1 1 auto;
      min-width: 0;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
      color: var(--text-primary);
      font-size: var(--font-size-body);
    }
    /* Read-only here: it recedes, so the rules the referent edits stand out. */
    .block.locked {
      border-style: dashed;
      background: var(--surface-soft);
    }
    .block.locked .phrase {
      color: var(--text-secondary);
    }
    .block.off .phrase {
      color: var(--text-tertiary);
    }
    .block.missing {
      border-color: var(--status-danger);
    }
    .lock {
      flex: 0 0 auto;
      width: var(--icon-md);
      height: var(--icon-md);
      fill: none;
      stroke: var(--text-muted);
      stroke-width: 2;
      stroke-linecap: round;
    }
    :host ::ng-deep [blockError] {
      display: block;
      margin: var(--space-1) 0 0;
      color: var(--status-danger);
      font-size: var(--font-size-xs);
    }
    @include media.phone {
      .block {
        flex-wrap: wrap;
      }
    }
  `,
})
export class RuleBlockComponent {
  readonly locked = input(false);
  readonly removable = input(false);
  readonly removeLabel = input('Quitar condición');
  readonly missing = input(false);
  readonly off = input(false);
  readonly disabled = input(false);
  readonly remove = output<void>();
}
