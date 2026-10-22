import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

/** Pager for server-side paginated lists (Spring Data, 0-based `page`). */
@Component({
  selector: 'app-pagination',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="pager" aria-label="Paginación">
      <span class="summary">
        @if (totalElements() === 0) {
          Sin resultados
        } @else {
          <strong class="tabular">{{ rangeStart() }}–{{ rangeEnd() }}</strong> de
          <strong class="tabular">{{ totalElements() }}</strong>
        }
      </span>

      <div class="controls">
        <div class="size-picker" role="group" aria-label="Expedientes por página">
          <span class="size-label">Por página</span>
          <div class="segmented">
            @for (s of sizeOptions(); track s) {
              <button
                type="button"
                class="seg"
                [class.active]="s === size()"
                [attr.aria-pressed]="s === size()"
                (click)="s !== size() && sizeChange.emit(s)"
              >
                {{ s }}
              </button>
            }
          </div>
        </div>

        <div class="segmented pages">
          <button
            type="button"
            class="seg arrow"
            aria-label="Página anterior"
            [disabled]="page() === 0"
            (click)="pageChange.emit(page() - 1)"
          >
            ‹
          </button>
          @for (p of pageItems(); track $index) {
            @if (p === null) {
              <span class="gap" aria-hidden="true">…</span>
            } @else {
              <button
                type="button"
                class="seg"
                [class.active]="p === page()"
                [attr.aria-current]="p === page() ? 'page' : null"
                [attr.aria-label]="'Página ' + (p + 1)"
                (click)="p !== page() && pageChange.emit(p)"
              >
                {{ p + 1 }}
              </button>
            }
          }
          <button
            type="button"
            class="seg arrow"
            aria-label="Página siguiente"
            [disabled]="page() + 1 >= pageCount()"
            (click)="pageChange.emit(page() + 1)"
          >
            ›
          </button>
        </div>
      </div>
    </nav>
  `,
  styles: `
    @use 'media';

    :host {
      display: block;
      margin-top: var(--space-4);
    }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
    }
    .summary {
      font-size: var(--font-size-sm);
      color: var(--text-muted);
    }
    .summary strong {
      color: var(--text-primary);
      font-weight: var(--font-weight-medium);
    }
    .controls {
      display: flex;
      align-items: center;
      gap: var(--space-4);
      flex-wrap: wrap;
    }
    .size-picker {
      display: flex;
      align-items: center;
      gap: var(--space-2);
    }
    .size-label {
      font-size: var(--font-size-sm);
      color: var(--text-muted);
    }

    /* One raised strip; the selected segment is a teal pill inside it. */
    .segmented {
      display: inline-flex;
      align-items: center;
      gap: var(--space-1);
      padding: var(--space-1);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: linear-gradient(180deg, var(--surface), var(--surface-soft));
      box-shadow: var(--shadow-btn);
    }
    .seg {
      min-width: calc(var(--space-6) - var(--space-1));
      padding: var(--space-1) var(--space-2);
      border: 1px solid transparent;
      border-radius: calc(var(--radius-ctl) - var(--space-1) / 2);
      background: transparent;
      font: inherit;
      font-size: var(--font-size-sm);
      font-variant-numeric: tabular-nums;
      color: var(--text-secondary);
      cursor: pointer;
      transition:
        background-color var(--dur-1) ease,
        color var(--dur-1) ease,
        box-shadow var(--dur-2) var(--ease-standard);
    }
    .seg:hover:not(:disabled):not(.active) {
      background: color-mix(in srgb, var(--text-primary) 6%, transparent);
      color: var(--text-primary);
    }
    .seg:active:not(:disabled) {
      box-shadow: var(--shadow-pressed);
    }
    .seg:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
    }
    .seg.active {
      border-color: var(--selected-border);
      background: var(--selected-bg);
      color: var(--accent-fg);
      font-weight: var(--font-weight-medium);
      cursor: default;
    }
    .seg.arrow {
      font-size: var(--font-size-md);
      line-height: 1;
    }
    .seg:disabled {
      color: var(--text-muted);
      opacity: 0.45;
      cursor: default;
    }
    .gap {
      padding: 0 var(--space-1);
      color: var(--text-muted);
    }
    @media (prefers-reduced-motion: reduce) {
      .seg {
        transition: none;
      }
    }

    @include media.touch {
      .seg {
        min-height: var(--touch-target);
        min-width: var(--touch-target);
      }
    }
    @include media.phone {
      .pager,
      .controls {
        width: 100%;
      }
      .controls {
        justify-content: space-between;
      }
    }
  `,
})
export class PaginationComponent {
  readonly page = input(0);
  readonly totalPages = input(0);
  readonly totalElements = input(0);
  readonly size = input(20);
  readonly sizeOptions = input<number[]>([10, 20, 50]);

  readonly pageChange = output<number>();
  readonly sizeChange = output<number>();

  protected readonly pageCount = computed(() => Math.max(1, this.totalPages()));

  protected readonly rangeStart = computed(() => this.page() * this.size() + 1);
  protected readonly rangeEnd = computed(() =>
    Math.min((this.page() + 1) * this.size(), this.totalElements()),
  );

  /** First, last and the neighbors of the current page; `null` where pages are skipped. */
  protected readonly pageItems = computed<(number | null)[]>(() => {
    const total = this.pageCount();
    const current = this.page();
    const shown = [...new Set([0, current - 1, current, current + 1, total - 1])]
      .filter((p) => p >= 0 && p < total)
      .sort((a, b) => a - b);
    const items: (number | null)[] = [];
    shown.forEach((p, i) => {
      if (i > 0 && p - shown[i - 1] > 1) items.push(null);
      items.push(p);
    });
    return items;
  });
}
