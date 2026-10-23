import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';

import { ChipGroupComponent, ChipOption } from '../chip-group/chip-group.component';
import { InputComponent } from '../input/input.component';
import { PopoverField } from './popover-field';

/** A part of the day for someone who does not remember the exact time. */
export interface TimeSlot {
  label: string;
  /** `HH:mm` the slot writes into the field. */
  time: string;
  /** `HH:mm` where the slot begins; it lasts until the next one does. */
  startsAt: string;
}

/**
 * Time field whose panel offers parts of the day next to the exact time. The slot only writes a
 * representative hour: the value is always an `HH:mm`, and the slot shown is derived from it.
 */
@Component({
  selector: 'app-time-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ChipGroupComponent, InputComponent],
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
        <circle cx="12" cy="12" r="8.5" stroke-width="1.6" />
        <path d="M12 7.5V12l3 2" stroke-width="1.6" stroke-linecap="round" />
      </svg>
      <span class="trigger-label tabular" [class.is-placeholder]="!label()">
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
        @if (slotOptions().length) {
          <div class="panel-section">
            <span class="t-field-label">Franja</span>
            <app-chip-group
              size="sm"
              [options]="slotOptions()"
              [value]="activeSlot()?.time ?? ''"
              ariaLabel="Franja"
              (valueChange)="chooseSlot($event)"
            />
          </div>
        }
        <label class="panel-section">
          <span class="t-field-label">Hora exacta</span>
          <app-input
            type="time"
            [value]="value()"
            (valueChange)="value.set($event)"
            (enter)="closePanel()"
          />
        </label>
      </div>
    }
  `,
  styleUrl: './picker-field.scss',
  styles: `
    .panel {
      width: 20rem;
      max-width: calc(100vw - 2 * var(--space-2));
    }
  `,
})
export class TimePickerComponent extends PopoverField {
  readonly value = model('');
  readonly placeholder = input('Elegí la hora');
  readonly slots = input<readonly TimeSlot[]>([]);

  protected readonly panelWidth = 320;
  protected readonly panelHeight = 200;

  /** Keyed by the hour each slot writes: chip-group selects by value. */
  protected readonly slotOptions = computed<ChipOption[]>(() =>
    this.slots().map((s) => ({ value: s.time, label: s.label })),
  );

  protected readonly activeSlot = computed<TimeSlot | null>(() => {
    const time = this.value();
    if (!/^\d{2}:\d{2}$/.test(time)) return null;
    const started = this.slots().filter((s) => s.startsAt <= time);
    return started.length ? started[started.length - 1] : null;
  });

  protected readonly label = computed(() => {
    const time = this.value();
    if (!time) return '';
    const slot = this.activeSlot();
    return slot ? `${time} · ${slot.label}` : time;
  });

  protected chooseSlot(time: string): void {
    if (!time) return;
    this.value.set(time);
    this.closePanel();
  }
}
