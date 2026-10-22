/** How many rows a home list shows, and the pixels added to each so they fill the card exactly. */
export interface RowFit {
  rows: number;
  extra: number;
}

/**
 * Fits a list card to the height its column has left, so the home is one screen with neither a
 * gap under the last row nor a scroll. The column must have a fixed height (`.home.fit`).
 *
 * @param col       the column holding the card
 * @param card      the card whose list is fitted; it must not grow, or its gap counts as chrome
 * @param list      the list inside the card; its rows are measured for the row height
 * @param available how many items there are to show
 * @param extraNow  the extra currently applied, taken off the measured row height
 * @param fallback  rows when the columns are stacked (phone): the page scrolls there anyway
 */
export function fitRows(
  col: HTMLElement,
  card: HTMLElement,
  list: HTMLElement,
  available: number,
  extraNow: number,
  fallback: number,
): RowFit | null {
  const cols = col.parentElement;
  if (!cols || col.offsetWidth >= cols.offsetWidth - 1) {
    return { rows: fallback, extra: 0 };
  }
  const row = list.firstElementChild as HTMLElement | null;
  if (!row) return null;
  // A column taller than the screen means its height follows the content: fitting rows to it
  // would grow it, and the next measure would add rows again, forever.
  if (col.getBoundingClientRect().bottom > window.innerHeight + 1) {
    return { rows: fallback, extra: 0 };
  }

  const gap = parseFloat(getComputedStyle(col).rowGap) || 0;
  const others = Array.from(col.children).filter((child) => child !== card) as HTMLElement[];
  // A growing card at its own size: otherwise it would count the height it is about to give up.
  const othersHeight = others.reduce(
    (sum, other) =>
      sum +
      (other.classList.contains('grow')
        ? contentHeight(other)
        : other.getBoundingClientRect().height),
    0,
  );
  const chrome = card.getBoundingClientRect().height - list.getBoundingClientRect().height;
  // The tallest row (all but the first carry a divider), rounded up: the spread must never add
  // up past the card.
  const tallest = Math.max(
    ...Array.from(list.children).map((item) => item.getBoundingClientRect().height),
  );
  const rowHeight = Math.ceil(tallest - extraNow);
  // Fractional pixels add up across cards; the floor keeps the card from overflowing by one.
  const room = Math.floor(col.clientHeight - othersHeight - gap * others.length - chrome);
  const rows = Math.max(1, Math.floor(room / rowHeight));
  // Spread only when the rows really fill the card; with fewer items the rest stays below.
  const extra = available >= rows ? Math.max(0, Math.floor((room - rows * rowHeight) / rows)) : 0;
  return { rows, extra };
}

/**
 * A growing card's height if it didn't grow. Not its section's `scrollHeight`: the card has
 * `min-height: 100%`, so that returns the stretched height, which shrinks as the fitted list grows;
 * the fit then moved the list again and, near a row boundary, it never settled (the row shook).
 * The body is a plain block, so its own `scrollHeight` is the content's.
 */
function contentHeight(card: HTMLElement): number {
  const section = card.querySelector<HTMLElement>('section');
  const body = section?.querySelector<HTMLElement>('.card-body');
  if (!section || !body) return card.offsetHeight;
  const style = getComputedStyle(section);
  const top = body.getBoundingClientRect().top - section.getBoundingClientRect().top;
  return (
    top + body.scrollHeight + parseFloat(style.paddingBottom) + parseFloat(style.borderBottomWidth)
  );
}
