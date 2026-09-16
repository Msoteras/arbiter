import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { InputComponent } from '../../../shared/ui/input/input.component';
import { SelectComponent } from '../../../shared/ui/select/select.component';
import { ReportFiltersStore } from './report-filters.store';

/**
 * The period and branch controls, shared by both report tabs.
 *
 * <p>The host is `display: contents` on purpose: the three fields have to be items of the tab's
 * own `.params` grid, next to the filter that tab adds (claim cause, risk band). Wrapping them in
 * a box of their own would break the row into two grids that never line up.
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
