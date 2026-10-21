import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';

import { ChipGroupComponent, ChipOption } from '../chip-group/chip-group.component';
import { CalendarComponent } from './calendar.component';
import { PopoverField } from './popover-field';
import { formatPickedDate } from './picked-date';

/** A one-click date, such as "Ayer", shown above the calendar. */
export interface DateShortcut {
  label: string;
  /** `yyyy-MM-dd`. */
  date: string;
}

/**
 * Date field with its own calendar, replacing `<app-input type="date">` where quick answers help:
 * the native picker cannot hold shortcuts, so they would sit apart from the field they fill.
 * The value is `yyyy-MM-dd`, the same the native field stores.
 */
@Component({
  selector: 'app-date-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CalendarComponent, ChipGroupComponent],
  template: `
    <button
      type="button"
      class="trigger"
      [id]="resolvedId()"
      aria-haspopup="dialog"
      [attr.aria-expanded]="open()"
      [attr.aria-label]="ariaLabel() ? ariaLabel() + ': ' + (label() || placeholder()) : null"
      [disabled]="disabled()"
      (click)="toggle()"
    >
      <svg
        class="trigger-icon"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        aria-hidden="true"
      >
        <rect x="3.5" y="5" width="17" height="15" rx="2" stroke-width="1.6" />
        <path d="M3.5 10h17M8 3v4M16 3v4" stroke-width="1.6" stroke-linecap="round" />
      </svg>
      <span class="trigger-label" [class.is-placeholder]="!label()">
        {{ label() || placeholder() }}
      </span>
    </button>

    @if (open()) {
      <div
        class="panel"
        role="dialog"
        [attr.aria-label]="ariaLabel()"
        [style.top.px]="panelPos().top"
        [style.bottom.px]="panelPos().bottom"
        [style.left.px]="panelPos().left"
        [style.max-height.px]="panelPos().maxHeight"
        (keydown)="onPanelKeydown($event)"
        (focusout)="onFocusOut($event)"
      >
        @if (shortcutOptions().length) {
          <app-chip-group
            size="sm"
            [options]="shortcutOptions()"
            [value]="activeShortcut()"
            [ariaLabel]="ariaLabel() ?? 'Atajos'"
            (valueChange)="choose($event)"
          />
        }
        <app-calendar [start]="value()" [min]="min()" [max]="max()" (pick)="choose($event)" />
      </div>
    }
  `,
  styleUrl: './picker-field.scss',
})
export class DatePickerComponent extends PopoverField {
  readonly value = model('');
  readonly min = input<string | null>(null);
  readonly max = input<string | null>(null);
  readonly placeholder = input('Elegí la fecha');
  readonly shortcuts = input<readonly DateShortcut[]>([]);

  protected readonly panelWidth = 300;
  protected readonly panelHeight = 400;

  /** Out-of-range shortcuts are left out rather than shown disabled. */
  private readonly usableShortcuts = computed(() => {
    const min = this.min();
    const max = this.max();
    return this.shortcuts().filter(
      (s) => s.date && (!min || s.date >= min) && (!max || s.date <= max),
    );
  });

  /** Keyed by date: chip-group selects by value, and a date is unique among the shortcuts. */
  protected readonly shortcutOptions = computed<ChipOption[]>(() =>
    this.usableShortcuts().map((s) => ({ value: s.date, label: s.label })),
  );

  protected readonly activeShortcut = computed(() =>
    this.usableShortcuts().some((s) => s.date === this.value()) ? this.value() : '',
  );

  protected readonly label = computed(() => {
    const value = this.value();
    if (!value) return '';
    const shortcut = this.usableShortcuts().find((s) => s.date === value);
    const date = formatPickedDate(value);
    return shortcut ? `${shortcut.label} · ${date}` : date;
  });

  protected choose(date: string): void {
    if (!date) return;
    this.value.set(date);
    this.closePanel();
  }
}
