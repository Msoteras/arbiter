import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

type Shape = 'text' | 'block' | 'table';

/**
 * Placeholder with the shape of the content that is on its way, so the screen doesn't jump when it
 * arrives. `text`: a paragraph of `lines`; `block`: one surface that fills the host (the consumer
 * sets its height); `table`: a header and `rows` × `columns` cells inside a card.
 */
@Component({
  selector: 'app-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="skeleton" role="status" aria-live="polite">
      <span class="sr-only">{{ label() }}</span>
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
    .line {
      height: var(--font-size-body);
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

  /** The last line is shorter, like the end of a paragraph. */
  protected readonly lineWidths = computed(() =>
    Array.from({ length: this.lines() }, (_, i) => (i === this.lines() - 1 ? 60 : 100)),
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
