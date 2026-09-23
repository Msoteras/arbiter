import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

/**
 * The thead/tbody are projected, so styling them needs ::ng-deep (still scoped to the host).
 *
 * `stickyHeader` also gives the table its own height and vertical scroll: the `overflow-x` needed
 * for horizontal scrolling makes the host a scroll container, so `position: sticky` sticks to it,
 * not to the page, and without a height of its own the header would scroll away with the rows.
 */
@Component({
  selector: 'app-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <table
      class="table"
      [class.fixed]="fixed()"
      [class.pinned]="pinFirstColumn()"
      [class.pinned-end]="pinLastColumn()"
      [class.more-right]="moreRight()"
      [class.sticky-head]="stickyHeader()"
      [class.scrolled]="scrolled()"
    >
      <ng-content />
    </table>
  `,
  // Scrollable region: it has to be reachable by keyboard, and a screen reader has to be told
  // what it is. Only while it actually scrolls — an extra tab stop on a table that fits is noise.
  host: {
    '(scroll)': 'onScroll()',
    '[style.max-height]': 'stickyHeader() ? maxHeight() : null',
    '[attr.tabindex]': 'overflowing() ? 0 : null',
    '[attr.role]': 'overflowing() ? "region" : null',
    '[attr.aria-label]': 'overflowing() ? scrollLabel() : null',
    '[class.overflowing]': 'overflowing()',
  },
  styles: `
    :host {
      display: block;
      overflow: auto;
      /* The shade on the right edge says there is more table: without it the only hint that
         content is missing is the scrollbar, which on a Mac isn't even drawn at rest. */
      background: linear-gradient(to left, var(--surface-sunken), transparent 60%) right /
        var(--space-5) 100% no-repeat;
    }
    :host(:not(.overflowing)) {
      background: none;
    }
    :host:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
      border-radius: var(--radius-ctl);
    }
    .table {
      width: 100%;
      border-collapse: separate;
      border-spacing: 0;
      font-size: var(--font-size-body);
    }
    .table.fixed {
      table-layout: fixed;
    }
    :host ::ng-deep .table th,
    :host ::ng-deep .table td {
      text-align: left;
      padding: var(--space-3) var(--space-4);
      white-space: nowrap;
    }
    :host ::ng-deep .table thead th {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--text-muted);
      background: var(--surface-head);
      border-bottom: 1px solid var(--border-default);
    }
    :host ::ng-deep .table tbody tr:not(:last-child) td {
      border-bottom: 1px solid var(--border-subtle);
    }

    /* Sticks to the top edge of the table, which is the element that scrolls. */
    :host ::ng-deep .table.sticky-head thead th {
      position: sticky;
      top: 0;
      z-index: 2;
    }

    /* Pinned cells need their own background, or the scrolled cells show through. */
    :host ::ng-deep .table.pinned th:first-child,
    :host ::ng-deep .table.pinned td:first-child {
      position: sticky;
      left: 0;
      z-index: 1;
      background: var(--surface);
    }
    :host ::ng-deep .table.pinned thead th:first-child {
      z-index: 3;
      background: var(--surface-head);
    }
    /* The border shows up only once something is hidden behind it; at rest it would be one line too many. */
    :host ::ng-deep .table.pinned.scrolled th:first-child,
    :host ::ng-deep .table.pinned.scrolled td:first-child {
      border-right: 1px solid var(--border-default);
    }

    :host ::ng-deep .table.pinned-end th:last-child,
    :host ::ng-deep .table.pinned-end td:last-child {
      position: sticky;
      right: 0;
      z-index: 1;
      background: var(--surface);
    }
    :host ::ng-deep .table.pinned-end thead th:last-child {
      z-index: 3;
      background: var(--surface-head);
    }
    :host ::ng-deep .table.pinned-end.more-right th:last-child,
    :host ::ng-deep .table.pinned-end.more-right td:last-child {
      border-left: 1px solid var(--border-default);
    }
  `,
})
export class TableComponent {
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);

  /** Columns with no declared width end up equal (useful for matrices). */
  readonly fixed = input(false);
  readonly pinFirstColumn = input(false);
  readonly pinLastColumn = input(false);
  readonly stickyHeader = input(false);
  /** Only applies with `stickyHeader`. */
  readonly maxHeight = input('70vh');
  /** Accessible name of the scrollable region. */
  readonly scrollLabel = input('Tabla desplazable');

  private readonly scrollLeft = signal(0);
  private readonly scrollWidth = signal(0);
  private readonly clientWidth = signal(0);

  protected readonly scrolled = computed(() => this.scrollLeft() > 0);
  protected readonly moreRight = computed(
    () => this.scrollLeft() + this.clientWidth() < this.scrollWidth() - 1,
  );
  protected readonly overflowing = computed(() => this.scrollWidth() > this.clientWidth());

  constructor() {
    const destroyRef = inject(DestroyRef);
    // Observe both: the host resizes with the window, the table with its rows (paging, filtering).
    afterNextRender(() => {
      const element = this.host.nativeElement;
      const observer = new ResizeObserver(() => this.measure(element));
      observer.observe(element);
      const table = element.querySelector('table');
      if (table) {
        observer.observe(table);
      }
      destroyRef.onDestroy(() => observer.disconnect());
      this.measure(element);
    });
  }

  protected onScroll(): void {
    const element = this.host.nativeElement;
    this.scrollLeft.set(element.scrollLeft);
    this.measure(element);
  }

  private measure(element: HTMLElement): void {
    this.scrollWidth.set(element.scrollWidth);
    this.clientWidth.set(element.clientWidth);
  }
}
