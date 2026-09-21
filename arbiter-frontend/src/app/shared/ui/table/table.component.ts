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
 * The design system's table. The content (real thead/tbody with th/td) is projected as plain
 * HTML — this component only adds the standard look (faint uppercase header, rows separated by a
 * border). ::ng-deep is needed because that content arrives through <ng-content>, which Angular's
 * style encapsulation doesn't reach any other way; it stays scoped to the host.
 *
 * `fixed` splits the width per column instead of per content: the ones with no declared width end
 * up equal. For a matrix (the documentation schedule, document × claim cause) that is what fits —
 * by content, each column measures whatever its title measures and the grid looks crooked.
 *
 * A table that doesn't fit widthwise scrolls, and `pinFirstColumn` and `stickyHeader` are what
 * make that scroll usable: without the first column in view you can't tell which row you are
 * reading, and without the header, which column. Both are opt-in — a narrow table needs neither.
 *
 * `stickyHeader` also gives the table its own height (`maxHeight`) and vertical scroll. Not a
 * whim: the `overflow-x` the horizontal scroll needs already makes the table a scroll container,
 * so a `position: sticky` here sticks to IT and not to the page — with no height of its own the
 * header rides up with the rows and sticks to nothing. With its own viewport the header stays
 * above the rows while the table is read, which is the point.
 */
@Component({
  selector: 'app-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <table
      class="table"
      [class.fixed]="fixed()"
      [class.pinned]="pinFirstColumn()"
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

    /* Pinned first column: while the table scrolls sideways, the case number stays in view. It
       needs a background of its own — otherwise the cells next to it show through. */
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
  `,
})
export class TableComponent {
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);

  /** Width per column instead of per content: columns with no declared width end up equal. */
  readonly fixed = input(false);
  /** Keeps the first column in view while the table scrolls sideways. */
  readonly pinFirstColumn = input(false);
  /** Gives the table its own height and keeps the header in view while it is read. */
  readonly stickyHeader = input(false);
  /** Height of that own viewport, only with `stickyHeader`. Too much of it = a page with two scrolls. */
  readonly maxHeight = input('70vh');
  /** What this table is, for the screen reader that lands on the scrollable region. */
  readonly scrollLabel = input('Tabla desplazable');

  private readonly scrollLeft = signal(0);
  private readonly scrollWidth = signal(0);
  private readonly clientWidth = signal(0);

  protected readonly scrolled = computed(() => this.scrollLeft() > 0);
  protected readonly overflowing = computed(() => this.scrollWidth() > this.clientWidth());

  constructor() {
    const destroyRef = inject(DestroyRef);
    // The width comes from the projected content, so it is only known once rendered. Both are
    // observed: the host changes with the window, and the table with its rows and columns
    // (paging, filtering) without the host moving at all.
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
