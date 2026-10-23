import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { SpinnerComponent } from '../spinner/spinner.component';

type Shape = 'text' | 'block' | 'table' | 'card';

/**
 * Placeholder with the shape of the content that is on its way, so the screen doesn't jump when it
 * arrives. `text`: a paragraph of `lines`; `block`: one surface that fills the host (the consumer
 * sets its height); `table`: a header and `rows` × `columns` cells inside a card; `card`: a card with
 * a heading and `rows` list rows. `note` shows a spinner and that text above the shape, for a load
 * long enough to need saying what is on its way.
 */
@Component({
  selector: 'app-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpinnerComponent],
  template: `
    <div class="skeleton" role="status" aria-live="polite">
      @if (note()) {
        <p class="note"><app-spinner [size]="16" />{{ note() }}</p>
      } @else {
        <span class="sr-only">{{ label() }}</span>
      }
      @switch (shape()) {
        @case ('text') {
          @for (width of lineWidths(); track $index) {
            <span class="bone line" [style.width.%]="width"></span>
          }
        }
        @case ('block') {
          <span class="bone block"></span>
        }
        @case ('table') {
          <div class="table">
            <div class="row head" [style.grid-template-columns]="grid()">
              @for (col of columnList(); track $index) {
                <span class="bone cell short"></span>
              }
            </div>
            @for (row of rowList(); track $index) {
              <div class="row" [style.grid-template-columns]="grid()">
                @for (col of columnList(); track $index) {
                  <span class="bone cell" [style.width.%]="cellWidth(row, col)"></span>
                }
              </div>
            }
          </div>
        }
        @case ('card') {
          <div class="card">
            <span class="bone line title"></span>
            @for (row of rowList(); track $index) {
              <span class="bone list-row"></span>
            }
          </div>
        }
      }
    </div>
  `,
  styles: `
    /* Flex, not block: a consumer that only sets min-height still stretches a "block" bone. */
    :host {
      display: flex;
      flex-direction: column;
    }
    .skeleton {
      flex: 1 1 auto;
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .note {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      margin: 0 0 var(--space-1);
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
    }
    .bone {
      display: block;
      border-radius: var(--radius-ctl);
      /* Border tones, not surfaces: it must read both on a card and on the sunken canvas. */
      background: linear-gradient(
        90deg,
        var(--border-default) 0%,
        var(--border-subtle) 40%,
        var(--border-default) 80%
      );
      background-size: 300% 100%;
      animation: bone-shimmer 1.4s var(--ease-standard) infinite;
    }
    /* --skeleton-line-h lets a consumer match a bigger text, like a page title. */
    .line {
      height: var(--skeleton-line-h, var(--font-size-body));
    }
    .block {
      flex: 1 1 auto;
      min-height: var(--space-7);
      border-radius: var(--radius-card);
    }
    .table {
      border: 1px solid var(--border-default);
      border-radius: var(--radius-card);
      background: var(--surface);
      box-shadow: var(--shadow-card);
      overflow: hidden;
    }
    .card {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
      padding: var(--space-5);
      border: 1px solid var(--border-default);
      border-radius: var(--radius-card);
      background: var(--surface);
      box-shadow: var(--shadow-card);
    }
    .title {
      width: 35%;
      height: var(--font-size-md);
      margin-bottom: var(--space-2);
    }
    .list-row {
      height: var(--space-6);
    }
    .list-row:nth-child(2n) {
      animation-delay: 80ms;
    }
    .list-row:nth-child(3n) {
      animation-delay: 160ms;
    }
    .row {
      display: grid;
      gap: var(--space-4);
      align-items: center;
      padding: var(--space-3) var(--space-4);
      border-top: 1px solid var(--border-subtle);
    }
    .row.head {
      border-top: 0;
      background: var(--surface-head);
    }
    .cell {
      height: var(--font-size-body);
    }
    .cell.short {
      width: 50%;
      height: var(--font-size-2xs);
    }
    /* Staggered so the shimmer reads as a wave down the rows, not as one blinking block. */
    .row:nth-child(2n) .bone {
      animation-delay: 80ms;
    }
    .row:nth-child(3n) .bone {
      animation-delay: 160ms;
    }
    @keyframes bone-shimmer {
      from {
        background-position: 100% 0;
      }
      to {
        background-position: 0 0;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .bone {
        animation: none;
      }
    }
  `,
})
export class SkeletonComponent {
  readonly shape = input<Shape>('text');
  readonly lines = input(3);
  readonly rows = input(6);
  readonly columns = input(5);
  /** What the screen reader announces. */
  readonly label = input('Cargando');
  /** Visible "Cargando…" line above the shape; it replaces `label` for the screen reader. */
  readonly note = input('');

  /** The last line of a paragraph is shorter; a single line is as wide as the host. */
  protected readonly lineWidths = computed(() =>
    Array.from({ length: this.lines() }, (_, i) => (i === this.lines() - 1 && i > 0 ? 60 : 100)),
  );
  protected readonly rowList = computed(() => Array.from({ length: this.rows() }, (_, i) => i));
  protected readonly columnList = computed(() =>
    Array.from({ length: this.columns() }, (_, i) => i),
  );
  protected readonly grid = computed(() => `repeat(${this.columns()}, minmax(0, 1fr))`);

  /** Deterministic variation, so the cells don't line up into stripes nor reshuffle on render. */
  protected cellWidth(row: number, col: number): number {
    return 45 + (((row + 1) * (col + 3) * 37) % 50);
  }
}
