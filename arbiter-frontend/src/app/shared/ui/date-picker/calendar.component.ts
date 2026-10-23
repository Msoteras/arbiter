import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from '@angular/core';

import { addDays, todayIso } from '../../../core/util/datetime';

interface DayCell {
  date: string;
  day: number;
  label: string;
  disabled: boolean;
  selected: boolean;
  inRange: boolean;
  today: boolean;
}

const WEEKDAYS = [
  { short: 'L', long: 'lunes' },
  { short: 'M', long: 'martes' },
  { short: 'M', long: 'miércoles' },
  { short: 'J', long: 'jueves' },
  { short: 'V', long: 'viernes' },
  { short: 'S', long: 'sábado' },
  { short: 'D', long: 'domingo' },
];

/** `yyyy-MM` shifted by whole months. */
function shiftMonth(month: string, delta: number): string {
  const [year, m] = month.split('-').map(Number);
  const shifted = new Date(Date.UTC(year, m - 1 + delta, 1));
  return shifted.toISOString().slice(0, 7);
}

/** Dates are handled in UTC, like `addDays`: a local Date crossing DST lands on the previous day. */
function utc(isoDate: string): Date {
  return new Date(`${isoDate}T00:00:00Z`);
}

/**
 * Month grid behind the date pickers. Dates are `yyyy-MM-dd` strings, as the fields store them.
 * Single date: pass the same value as `start` and leave `end` empty. A range marks the days between
 * `start` and `end`; with `previewAnchor` set, hovering a day previews the range from the anchor.
 */
@Component({
  selector: 'app-calendar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cal">
      <div class="cal-head">
        <button
          type="button"
          class="nav"
          aria-label="Mes anterior"
          [disabled]="!canGoBack()"
          (click)="viewMonth.set(shift(viewMonth(), -1))"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
            <polyline points="15 6 9 12 15 18" stroke-width="1.8" stroke-linecap="round" />
          </svg>
        </button>
        <span class="cal-title" aria-live="polite">{{ monthLabel() }}</span>
        <button
          type="button"
          class="nav"
          aria-label="Mes siguiente"
          [disabled]="!canGoForward()"
          (click)="viewMonth.set(shift(viewMonth(), 1))"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
            <polyline points="9 6 15 12 9 18" stroke-width="1.8" stroke-linecap="round" />
          </svg>
        </button>
      </div>

      <div class="grid">
        @for (wd of weekdays; track wd.long) {
          <abbr class="wd" [title]="wd.long">{{ wd.short }}</abbr>
        }
        @for (pad of leadingBlanks(); track $index) {
          <span></span>
        }
        @for (cell of cells(); track cell.date) {
          <button
            type="button"
            class="day"
            [attr.data-date]="cell.date"
            [class.selected]="cell.selected"
            [class.in-range]="cell.inRange"
            [class.today]="cell.today"
            [attr.aria-label]="cell.label"
            [attr.aria-pressed]="cell.selected"
            [attr.aria-current]="cell.today ? 'date' : null"
            [attr.data-autofocus]="cell.date === focusDate() ? '' : null"
            [tabindex]="cell.date === focusDate() ? 0 : -1"
            [disabled]="cell.disabled"
            (click)="pick.emit(cell.date)"
            (mouseenter)="hovered.set(cell.date)"
            (mouseleave)="hovered.set(null)"
            (keydown)="onKeydown($event, cell.date)"
          >
            {{ cell.day }}
          </button>
        }
      </div>
    </div>
  `,
  styles: `
    @use 'media';

    :host {
      display: block;
    }
    .cal-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
      margin-bottom: var(--space-2);
    }
    .cal-title {
      font-weight: var(--font-weight-medium);
      color: var(--text-primary);
    }
    .nav {
      display: grid;
      place-items: center;
      width: 2.25rem;
      height: 2.25rem;
      padding: 0;
      border: none;
      border-radius: var(--radius-ctl);
      background: none;
      color: var(--text-secondary);
      cursor: pointer;
    }
    .nav svg {
      width: 1.1rem;
      height: 1.1rem;
    }
    .nav:hover:not(:disabled) {
      background: var(--surface-sunken);
      color: var(--text-primary);
    }
    .nav:disabled {
      opacity: 0.35;
      cursor: default;
    }
    .nav:focus-visible,
    .day:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
    }

    .grid {
      display: grid;
      grid-template-columns: repeat(7, 2.25rem);
      gap: 2px 0;
    }
    .wd {
      display: grid;
      place-items: center;
      height: 1.75rem;
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      text-decoration: none;
    }
    .day {
      width: 2.25rem;
      height: 2.25rem;
      padding: 0;
      border: none;
      border-radius: var(--radius-ctl);
      background: none;
      font: inherit;
      font-size: var(--font-size-sm);
      font-variant-numeric: tabular-nums;
      color: var(--text-primary);
      cursor: pointer;
    }
    .day:hover:not(:disabled):not(.selected) {
      background: var(--surface-sunken);
    }
    .day.today {
      font-weight: var(--font-weight-bold);
      text-decoration: underline;
      text-underline-offset: 3px;
    }
    .day.in-range {
      border-radius: 0;
      background: var(--selected-bg);
      color: var(--accent-fg);
    }
    .day.selected {
      background: var(--accent-fill);
      color: var(--text-on-emphasis);
      font-weight: var(--font-weight-medium);
    }
    .day:disabled {
      color: var(--text-muted);
      opacity: 0.45;
      cursor: default;
    }

    @include media.touch {
      .grid {
        grid-template-columns: repeat(7, var(--touch-target));
      }
      .day,
      .nav {
        width: var(--touch-target);
        height: var(--touch-target);
      }
    }
  `,
})
export class CalendarComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly start = input('');
  readonly end = input('');
  readonly min = input<string | null>(null);
  readonly max = input<string | null>(null);
  readonly previewAnchor = input<string | null>(null);
  readonly pick = output<string>();

  protected readonly weekdays = WEEKDAYS;
  protected readonly shift = shiftMonth;
  private readonly today = todayIso();

  protected readonly hovered = signal<string | null>(null);

  /** The day that takes Tab focus: the selection, else today kept within the allowed range. */
  protected readonly focusDate = linkedSignal(
    () => this.start() || this.end() || this.clamp(this.today),
  );
  protected readonly viewMonth = linkedSignal(() => this.focusDate().slice(0, 7));

  protected readonly monthLabel = computed(() => {
    const text = utc(`${this.viewMonth()}-01`).toLocaleDateString('es-AR', {
      month: 'long',
      year: 'numeric',
      timeZone: 'UTC',
    });
    return text.charAt(0).toUpperCase() + text.slice(1);
  });

  protected readonly canGoBack = computed(() => {
    const min = this.min();
    return !min || this.viewMonth() > min.slice(0, 7);
  });
  protected readonly canGoForward = computed(() => {
    const max = this.max();
    return !max || this.viewMonth() < max.slice(0, 7);
  });

  /** Weeks start on Monday, as calendars do in Argentina. */
  protected readonly leadingBlanks = computed(() => {
    const weekday = utc(`${this.viewMonth()}-01`).getUTCDay();
    return Array.from({ length: (weekday + 6) % 7 });
  });

  protected readonly cells = computed<DayCell[]>(() => {
    const month = this.viewMonth();
    const days = utc(`${shiftMonth(month, 1)}-01`);
    days.setUTCDate(0);
    const count = days.getUTCDate();
    const [low, high] = this.rangeBounds();
    const start = this.start();
    const end = this.end();
    return Array.from({ length: count }, (_, i) => {
      const date = `${month}-${String(i + 1).padStart(2, '0')}`;
      return {
        date,
        day: i + 1,
        label: utc(date).toLocaleDateString('es-AR', {
          weekday: 'long',
          day: 'numeric',
          month: 'long',
          year: 'numeric',
          timeZone: 'UTC',
        }),
        disabled: !this.allowed(date),
        selected: date === start || date === end,
        inRange: !!low && !!high && date > low && date < high,
        today: date === this.today,
      };
    });
  });

  /** The marked span: the chosen range, or the preview from the anchor to the hovered day. */
  private readonly rangeBounds = computed<[string, string]>(() => {
    const anchor = this.previewAnchor();
    const hovered = this.hovered();
    if (anchor && hovered) {
      return hovered < anchor ? [hovered, anchor] : [anchor, hovered];
    }
    return [this.start(), this.end()];
  });

  protected onKeydown(event: KeyboardEvent, date: string): void {
    const moves: Record<string, () => string> = {
      ArrowLeft: () => addDays(date, -1),
      ArrowRight: () => addDays(date, 1),
      ArrowUp: () => addDays(date, -7),
      ArrowDown: () => addDays(date, 7),
      Home: () => addDays(date, -((utc(date).getUTCDay() + 6) % 7)),
      End: () => addDays(date, 6 - ((utc(date).getUTCDay() + 6) % 7)),
      PageUp: () => this.sameDayIn(date, -1),
      PageDown: () => this.sameDayIn(date, 1),
    };
    const move = moves[event.key];
    if (!move) return;
    event.preventDefault();
    const next = this.clamp(move());
    this.focusDate.set(next);
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLElement>(`[data-date="${next}"]`)?.focus(),
      { injector: this.injector },
    );
  }

  /** The 31st one month later lands on the last day of that month. */
  private sameDayIn(date: string, delta: number): string {
    const month = shiftMonth(date.slice(0, 7), delta);
    const last = utc(`${shiftMonth(month, 1)}-01`);
    last.setUTCDate(0);
    const day = Math.min(Number(date.slice(8)), last.getUTCDate());
    return `${month}-${String(day).padStart(2, '0')}`;
  }

  private allowed(date: string): boolean {
    const min = this.min();
    const max = this.max();
    return (!min || date >= min) && (!max || date <= max);
  }

  private clamp(date: string): string {
    const min = this.min();
    const max = this.max();
    if (min && date < min) return min;
    if (max && date > max) return max;
    return date;
  }
}
