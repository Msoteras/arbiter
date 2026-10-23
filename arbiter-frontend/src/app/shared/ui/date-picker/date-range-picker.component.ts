import { ChangeDetectionStrategy, Component, computed, input, model, signal } from '@angular/core';

import { ButtonComponent } from '../button/button.component';
import { ChipGroupComponent, ChipOption } from '../chip-group/chip-group.component';
import { CalendarComponent } from './calendar.component';
import { PopoverField } from './popover-field';
import { formatShortDate } from './picked-date';

/** A ready-made range, such as "Últimos 30 días". Either end may be empty. */
export interface DateRangePreset {
  label: string;
  from: string;
  to: string;
}

type RangeEnd = 'from' | 'to';

/**
 * Date range in a single field. Either end can stay empty, which leaves that side open: "Desde" alone
 * means from that day on. The panel marks which end the next click on the calendar sets; picking
 * "Desde" moves on to "Hasta", and picking "Hasta" closes the panel.
 */
@Component({
  selector: 'app-date-range-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonComponent, CalendarComponent, ChipGroupComponent],
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
        @if (presetOptions().length) {
          <app-chip-group
            size="sm"
            [options]="presetOptions()"
            [value]="activePreset()"
            ariaLabel="Rangos frecuentes"
            (valueChange)="applyPreset($event)"
          />
        }

        <div class="ends">
          @for (end of ends; track end.key) {
            <div class="end" [class.active]="active() === end.key">
              <button
                type="button"
                class="end-pick"
                [attr.aria-pressed]="active() === end.key"
                (click)="active.set(end.key)"
              >
                <span class="end-label">{{ end.label }}</span>
                <span class="end-value tabular" [class.open-end]="!endValue(end.key)">
                  {{ endValue(end.key) ? short(endValue(end.key)) : 'Sin límite' }}
                </span>
              </button>
              @if (endValue(end.key)) {
                <button
                  type="button"
                  class="end-clear"
                  [attr.aria-label]="'Quitar la fecha ' + end.label.toLowerCase()"
                  (click)="clearEnd(end.key)"
                >
                  ✕
                </button>
              }
            </div>
          }
        </div>

        <app-calendar
          [start]="from()"
          [end]="to()"
          [max]="max()"
          [previewAnchor]="active() === 'to' && from() && !to() ? from() : null"
          (pick)="pickDay($event)"
        />

        <div class="panel-actions">
          <app-button variant="secondary" size="sm" (click)="clear()">Limpiar</app-button>
          <app-button size="sm" (click)="closePanel()">Listo</app-button>
        </div>
      </div>
    }
  `,
  styleUrl: './picker-field.scss',
  styles: `
    .ends {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--space-2);
    }
    .end {
      position: relative;
      min-width: 0;
    }
    .end-pick {
      width: 100%;
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 2px;
      padding: var(--space-2) var(--space-3);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: var(--surface);
      font: inherit;
      text-align: left;
      cursor: pointer;
    }
    .end.active .end-pick {
      border-color: var(--selected-border);
      background: var(--selected-bg);
    }
    .end-pick:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
    }
    .end-label {
      font-size: var(--font-size-2xs);
      font-weight: var(--font-weight-medium);
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: var(--text-muted);
    }
    .end.active .end-label {
      color: var(--accent-fg);
    }
    .end-value {
      font-size: var(--font-size-sm);
      color: var(--text-primary);
    }
    .end-value.open-end {
      color: var(--text-muted);
    }
    .end-clear {
      position: absolute;
      top: var(--space-1);
      right: var(--space-1);
      padding: var(--space-1);
      border: none;
      border-radius: var(--radius-ctl);
      background: none;
      font-size: var(--font-size-2xs);
      line-height: 1;
      color: var(--text-muted);
      cursor: pointer;
    }
    .end-clear:hover {
      color: var(--text-primary);
    }
    .end-clear:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
    }
  `,
})
export class DateRangePickerComponent extends PopoverField {
  readonly from = model('');
  readonly to = model('');
  readonly max = input<string | null>(null);
  readonly placeholder = input('Cualquier fecha');
  readonly presets = input<readonly DateRangePreset[]>([]);

  protected readonly panelWidth = 340;
  protected readonly panelHeight = 520;

  protected readonly ends: readonly { key: RangeEnd; label: string }[] = [
    { key: 'from', label: 'Desde' },
    { key: 'to', label: 'Hasta' },
  ];
  protected readonly active = signal<RangeEnd>('from');
  protected readonly short = formatShortDate;

  protected readonly presetOptions = computed<ChipOption[]>(() =>
    this.presets().map((p) => ({ value: p.label, label: p.label })),
  );

  private readonly matchingPreset = computed(() =>
    this.presets().find((p) => p.from === this.from() && p.to === this.to()),
  );

  protected readonly activePreset = computed(() => this.matchingPreset()?.label ?? '');

  protected readonly label = computed(() => {
    const preset = this.matchingPreset();
    if (preset) return preset.label;
    const from = this.from();
    const to = this.to();
    if (from && to) return `${formatShortDate(from)} – ${formatShortDate(to)}`;
    if (from) return `Desde ${formatShortDate(from)}`;
    if (to) return `Hasta ${formatShortDate(to)}`;
    return '';
  });

  protected endValue(end: RangeEnd): string {
    return end === 'from' ? this.from() : this.to();
  }

  /** With only "Desde" chosen, the next click most likely sets "Hasta". */
  protected override onOpen(): void {
    this.active.set(this.from() && !this.to() ? 'to' : 'from');
  }

  protected pickDay(date: string): void {
    if (this.active() === 'from') {
      this.from.set(date);
      if (this.to() && date > this.to()) {
        this.to.set('');
      }
      this.active.set('to');
      return;
    }
    // A "Hasta" before "Desde" is read as a new start: nobody means a backwards range.
    if (this.from() && date < this.from()) {
      this.from.set(date);
      return;
    }
    this.to.set(date);
    this.closePanel();
  }

  protected applyPreset(label: string): void {
    const preset = this.presets().find((p) => p.label === label);
    if (!preset) return;
    this.from.set(preset.from);
    this.to.set(preset.to);
    this.closePanel();
  }

  protected clearEnd(end: RangeEnd): void {
    (end === 'from' ? this.from : this.to).set('');
    this.active.set(end);
  }

  protected clear(): void {
    this.from.set('');
    this.to.set('');
    this.active.set('from');
  }
}
