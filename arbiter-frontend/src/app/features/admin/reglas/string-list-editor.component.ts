import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';

/**
 * Emits the whole list on every change; the parent keeps it in its draft. Local to the rules
 * feature, not a kit primitive. Rows are read-only and edited one at a time.
 * With `emptyTitle` the empty list shows the full empty state; without it, just the `emptyText` line.
 */
@Component({
  selector: 'app-string-list-editor',
  imports: [ButtonComponent, InputComponent, BadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (items().length === 0 && emptyTitle()) {
      <div class="empty-box">
        <span class="empty-icon" aria-hidden="true">
          <ng-content select="[emptyIcon]" />
        </span>
        <p class="empty-title">{{ emptyTitle() }}</p>
        @if (emptyHint()) {
          <p class="empty-hint">{{ emptyHint() }}</p>
        }
        <app-button (click)="add()">{{ emptyCta() || addLabel() }}</app-button>
      </div>
    } @else {
      <ul class="items">
        @for (item of items(); track $index) {
          <li class="item" [class.editing]="editing() === $index">
            @if (editing() === $index) {
              <app-input
                class="grow"
                [value]="item"
                (valueChange)="update($index, $event)"
                [placeholder]="placeholder()"
              />
              <button
                type="button"
                class="icon-btn confirm"
                [attr.aria-label]="'Listo'"
                (click)="stopEditing()"
              >
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2.2"
                  aria-hidden="true"
                >
                  <path d="M5 13l4 4L19 7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </button>
            } @else {
              @if (marker()) {
                <span class="row-marker" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
                    <line x1="18" y1="6" x2="6" y2="18" stroke-linecap="round" />
                    <line x1="6" y1="6" x2="18" y2="18" stroke-linecap="round" />
                  </svg>
                </span>
              }
              <div class="text">
                <p class="item-text">{{ item || placeholder() }}</p>
                @if (badge()) {
                  <app-badge variant="solid">{{ badge() }}</app-badge>
                }
              </div>
              <div class="row-actions">
                <button
                  type="button"
                  class="icon-btn"
                  [attr.aria-label]="'Editar'"
                  (click)="startEditing($index)"
                >
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.8"
                    aria-hidden="true"
                  >
                    <path d="M4 20h4L19 9l-4-4L4 16v4z" stroke-linejoin="round" />
                  </svg>
                </button>
                <button
                  type="button"
                  class="icon-btn danger"
                  [attr.aria-label]="'Quitar'"
                  (click)="remove($index)"
                >
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.8"
                    aria-hidden="true"
                  >
                    <path
                      d="M5 7h14M10 7V5h4v2M6 7l1 13h10l1-13"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                  </svg>
                </button>
              </div>
            }
          </li>
        } @empty {
          <li class="empty t-note">{{ emptyText() }}</li>
        }
      </ul>
      <app-button variant="secondary" size="sm" (click)="add()">{{ addLabel() }}</app-button>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .items {
      list-style: none;
      margin: 0 0 var(--space-2);
      padding: 0;
      display: flex;
      flex-direction: column;
    }
    .item {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      padding: var(--space-3) 0;
    }
    .item + .item {
      border-top: 1px solid var(--border-subtle);
    }
    .item.editing {
      gap: var(--space-2);
    }
    .grow {
      flex: 1 1 auto;
    }
    .text {
      flex: 1 1 auto;
      min-width: 0;
      display: flex;
      align-items: center;
      gap: var(--space-2);
      flex-wrap: wrap;
    }
    .item-text {
      margin: 0;
      color: var(--text-primary);
    }
    .empty {
      margin: 0 0 var(--space-1);
      padding: var(--space-2) 0;
    }

    /* Always visible (touch has no hover); color on the icon only, no fill. */
    .row-actions {
      flex: 0 0 auto;
      display: flex;
      gap: var(--space-1);
    }

    /* Decorative "not covered" mark, hence aria-hidden. */
    .row-marker {
      flex: 0 0 auto;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 22px;
      height: 22px;
      border-radius: var(--radius-ctl);
      /* Derived from the danger token rather than adding a palette color. */
      background: color-mix(in srgb, var(--status-danger) 10%, transparent);
      color: var(--status-danger);
    }
    .row-marker svg {
      width: 14px;
      height: 14px;
    }
    .icon-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 32px;
      height: 32px;
      padding: 0;
      background: none;
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      color: var(--accent-fg);
      cursor: pointer;
    }
    .icon-btn:hover {
      border-color: var(--selected-border);
      background: var(--selected-bg);
    }
    .icon-btn.danger {
      color: var(--status-danger);
    }
    .icon-btn.danger:hover {
      border-color: var(--status-danger);
      background: none;
    }
    .icon-btn:focus-visible {
      outline: none;
      border-color: var(--border-focus);
      box-shadow: var(--focus-ring);
    }
    .icon-btn svg {
      width: 16px;
      height: 16px;
    }

    .empty-box {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: var(--space-2);
      padding: var(--space-6) var(--space-4);
      border: 1px dashed var(--border-strong);
      border-radius: var(--radius-card);
      background: var(--surface-soft);
      text-align: center;
    }
    .empty-icon {
      color: var(--text-tertiary);
    }
    .empty-title {
      margin: 0;
      font-weight: var(--font-weight-medium);
      color: var(--text-primary);
    }
    .empty-hint {
      margin: 0;
      max-width: 46ch;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }

    @media (prefers-reduced-motion: reduce) {
      .row-actions {
        transition: none;
      }
    }
  `,
})
export class StringListEditorComponent {
  readonly items = input<string[]>([]);
  readonly placeholder = input('');
  readonly addLabel = input('+ Agregar');
  /** Shown when the list is empty and no `emptyTitle` is set. */
  readonly emptyText = input('');
  /** When set, an empty list shows the full empty state instead of `emptyText`. */
  readonly emptyTitle = input('');
  readonly emptyHint = input('');
  /** Empty-state button text; defaults to `addLabel`. */
  readonly emptyCta = input('');
  /** Same badge on every row: who consumes these texts (engine, analyst, LLM). */
  readonly badge = input('');
  /** Decorative leading mark on each row (the "not covered" ✗ in exclusions). */
  readonly marker = input(false);

  readonly itemsChange = output<string[]>();

  /** Row being edited; null = all read-only. One at a time. */
  protected readonly editing = signal<number | null>(null);

  protected startEditing(index: number): void {
    this.editing.set(index);
  }

  protected stopEditing(): void {
    this.editing.set(null);
  }

  protected add(): void {
    const next = [...this.items(), ''];
    // A new row starts in edit mode.
    this.editing.set(next.length - 1);
    this.itemsChange.emit(next);
  }

  protected update(index: number, value: string): void {
    this.itemsChange.emit(this.items().map((item, i) => (i === index ? value : item)));
  }

  protected remove(index: number): void {
    this.editing.set(null);
    this.itemsChange.emit(this.items().filter((_, i) => i !== index));
  }
}
