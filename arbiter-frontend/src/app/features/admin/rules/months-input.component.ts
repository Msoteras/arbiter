import {
  ChangeDetectionStrategy,
  Component,
  effect,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { InputComponent } from '../../../shared/ui/input/input.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';

type Unit = 'months' | 'years';

const MONTHS_PER: Record<Unit, number> = { months: 1, years: 12 };

/**
 * A period typed as a number plus a unit, stored in months. Local to the admin rules screens, not
 * a kit primitive. Emits `null` for an empty or non-numeric amount; range checks are the parent's.
 */
@Component({
  selector: 'app-months-input',
  imports: [InputComponent, SelectComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-input
      class="amount"
      type="number"
      [id]="id()"
      [min]="1"
      [value]="amount()"
      [placeholder]="placeholder()"
      [disabled]="disabled()"
      (valueChange)="setAmount($event)"
    />
    <app-select
      class="unit"
      [options]="unitOptions"
      [value]="unit()"
      [disabled]="disabled()"
      (valueChange)="setUnit($event)"
    />
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
    }
    .amount {
      width: var(--control-w-number);
    }
    .unit {
      width: var(--control-w-unit);
    }
  `,
})
export class MonthsInputComponent {
  readonly months = input<number | null>(null);
  readonly id = input<string | null>(null);
  readonly placeholder = input('');
  readonly disabled = input(false);

  readonly monthsChange = output<number | null>();

  protected readonly unitOptions: SelectOption[] = [
    { value: 'months', label: 'meses' },
    { value: 'years', label: 'años' },
  ];
  /** What's typed and its unit: view state, only their product in months leaves the component. */
  protected readonly amount = signal('');
  protected readonly unit = signal<Unit>('months');

  constructor() {
    // Re-reads the bound value only when it isn't what is already on screen (load, discard), so
    // typing "1" in years isn't rewritten as "12 meses" under the user.
    effect(() => {
      const months = this.months();
      untracked(() => {
        if (months !== this.typedMonths()) {
          this.show(months);
        }
      });
    });
  }

  protected setAmount(amount: string): void {
    this.amount.set(amount);
    this.monthsChange.emit(this.typedMonths());
  }

  /** Keeps the number and reinterprets it: "3 meses" becomes "3 años", not "0,25 años". */
  protected setUnit(unit: string): void {
    this.unit.set(unit as Unit);
    this.monthsChange.emit(this.typedMonths());
  }

  private typedMonths(): number | null {
    const amount = this.amount().trim();
    const value = Number(amount);
    return amount === '' || !Number.isFinite(value) ? null : value * MONTHS_PER[this.unit()];
  }

  /** Whole years read as years; anything else stays in months. An empty value keeps the unit. */
  private show(months: number | null): void {
    if (months == null) {
      this.amount.set('');
      return;
    }
    const unit: Unit = months > 0 && months % 12 === 0 ? 'years' : 'months';
    this.unit.set(unit);
    this.amount.set(String(months / MONTHS_PER[unit]));
  }
}
