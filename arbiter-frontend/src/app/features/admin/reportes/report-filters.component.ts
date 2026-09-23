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
      <label class="t-field-label" for="rep-desde">Desde</label>
      <app-input
        id="rep-desde"
        type="date"
        [max]="filters.today"
        [value]="filters.from()"
        (valueChange)="filters.from.set($event)"
      />
    </div>

    <div class="field">
      <label class="t-field-label" for="rep-hasta">Hasta</label>
      <app-input
        id="rep-hasta"
        type="date"
        [min]="filters.from()"
        [max]="filters.today"
        [value]="filters.to()"
        (valueChange)="filters.to.set($event)"
      />
    </div>

    <div class="field field-wide">
      <label class="t-field-label" for="rep-ramo">Ramo</label>
      <app-select
        id="rep-ramo"
        placeholder="Todos"
        [searchable]="true"
        [options]="filters.branchOptions()"
        [value]="filters.branchValue()"
        (valueChange)="filters.setBranch($event)"
      />
    </div>
  `,
  styleUrl: './report-params.scss',
})
export class ReportFiltersComponent {
  protected readonly filters = inject(ReportFiltersStore);
}
