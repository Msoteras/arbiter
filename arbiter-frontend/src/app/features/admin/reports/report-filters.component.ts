import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { InputComponent } from '../../../shared/ui/input/input.component';
import { SelectComponent } from '../../../shared/ui/select/select.component';
import { ReportFiltersStore } from './report-filters.store';

/**
 * The host is `display: contents` on purpose: the fields must be items of the tab's own `.params`
 * grid, next to the tab-specific filter.
 */
@Component({
  selector: 'app-report-filters',
  imports: [InputComponent, SelectComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { style: 'display: contents' },
  template: `
    <div class="field">
      <label class="t-field-label" for="rep-from">Desde</label>
      <app-input
        id="rep-from"
        type="date"
        [max]="filters.today"
        [value]="filters.from()"
        (valueChange)="filters.from.set($event)"
      />
    </div>

    <div class="field">
      <label class="t-field-label" for="rep-to">Hasta</label>
      <app-input
        id="rep-to"
        type="date"
        [min]="filters.from()"
        [max]="filters.today"
        [value]="filters.to()"
        (valueChange)="filters.to.set($event)"
      />
    </div>

    <div class="field field-wide">
      <label class="t-field-label" for="rep-branch">Ramo</label>
      <app-select
        id="rep-branch"
        placeholder="Todos"
        [searchable]="true"
        [options]="filters.branchOptions()"
        [value]="filters.branchValue()"
        (valueChange)="filters.setBranch($event)"
      />
    </div>

    <div class="field" [class.field-wide]="filters.comparisonMode() !== 'CUSTOM'">
      <label class="t-field-label" for="rep-compare">Comparar con</label>
      <app-select
        id="rep-compare"
        [options]="filters.comparisonOptions"
        [value]="filters.comparisonMode()"
        (valueChange)="filters.setComparisonMode($event)"
      />
    </div>

    @if (filters.comparisonMode() === 'CUSTOM') {
      <div class="field">
        <label class="t-field-label" for="rep-compare-from">Comparar desde</label>
        <app-input
          id="rep-compare-from"
          type="date"
          [max]="filters.today"
          [value]="filters.compareFrom()"
          (valueChange)="filters.compareFrom.set($event)"
        />
      </div>

      <div class="field">
        <label class="t-field-label" for="rep-compare-to">Comparar hasta</label>
        <app-input
          id="rep-compare-to"
          type="date"
          [min]="filters.compareFrom()"
          [max]="filters.today"
          [value]="filters.compareTo()"
          (valueChange)="filters.compareTo.set($event)"
        />
      </div>
    }
  `,
  styleUrl: './report-params.scss',
})
export class ReportFiltersComponent {
  protected readonly filters = inject(ReportFiltersStore);
}
