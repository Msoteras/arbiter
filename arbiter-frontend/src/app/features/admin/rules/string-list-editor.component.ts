import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { IconButtonComponent } from '../../../shared/ui/icon-button/icon-button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';

/**
 * Emits the whole list on every change; the parent keeps it in its draft. Local to the rules
 * feature, not a kit primitive. Rows are read-only until edited; several can be open at once.
 * With `emptyTitle` the empty list shows the full empty state; without it, just the `emptyText` line.
 */
@Component({
  selector: 'app-string-list-editor',
  imports: [IconButtonComponent, ButtonComponent, InputComponent],
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
        @if (!readOnly()) {
          <app-button (click)="add()">{{ emptyCta() || addLabel() }}</app-button>
        }
      </div>
    } @else {
      <ul class="items">
        @for (item of items(); track $index) {
          <li class="item" [class.editing]="editing().has($index)">
            @if (editing().has($index)) {
              <app-input
                class="grow"
                [value]="item"
                (valueChange)="update($index, $event)"
                (enter)="stopEditing($index)"
                [placeholder]="placeholder()"
              />
              <app-icon-button icon="confirm" label="Listo" (click)="stopEditing($index)" />
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
              </div>
              @if (!readOnly()) {
                <div class="row-actions">
                  <app-icon-button icon="edit" label="Editar" (click)="startEditing($index)" />
                  <app-icon-button icon="delete" label="Quitar" (click)="remove($index)" />
                </div>
              }
            }
          </li>
        } @empty {
          <li class="empty t-note">{{ emptyText() }}</li>
        }
      </ul>
      @if (!readOnly()) {
        <app-button variant="secondary" size="sm" (click)="add()">{{ addLabel() }}</app-button>
      }
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
      width: var(--icon-sm);
      height: var(--icon-sm);
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
  /** Decorative leading mark on each row (the "not covered" ✗ in exclusions). */
  readonly marker = input(false);
  readonly readOnly = input(false);

  readonly itemsChange = output<string[]>();

  /** Rows open for editing. A set: adding a row must not close the one still being typed. */
  protected readonly editing = signal<ReadonlySet<number>>(new Set());

  protected startEditing(index: number): void {
    this.editing.update((open) => new Set(open).add(index));
  }

  /** Confirming a blank row discards it: it would otherwise show the placeholder as if it were text. */
  protected stopEditing(index: number): void {
    if (!this.items()[index]?.trim()) {
      this.remove(index);
      return;
    }
    this.editing.update((open) => new Set([...open].filter((i) => i !== index)));
  }

  protected add(): void {
    const next = [...this.items(), ''];
    this.startEditing(next.length - 1);
    this.itemsChange.emit(next);
  }

  protected update(index: number, value: string): void {
    this.itemsChange.emit(this.items().map((item, i) => (i === index ? value : item)));
  }

  protected remove(index: number): void {
    // Rows below the removed one shift up, and so do their indexes.
    this.editing.update(
      (open) => new Set([...open].filter((i) => i !== index).map((i) => (i > index ? i - 1 : i))),
    );
    this.itemsChange.emit(this.items().filter((_, i) => i !== index));
  }
}
