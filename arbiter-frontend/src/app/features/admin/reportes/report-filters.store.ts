import { Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ParamMap, Params } from '@angular/router';

import { todayIso } from '../../../core/util/datetime';
import { SelectOption } from '../../../shared/ui/select/select.component';
import { BranchesService } from '../branches.service';
import { periodError } from './resolution-report';

/**
 * The filters both reports share: the period and the branch.
 *
 * <p>They live here and not in each tab because they have to survive the switch between them. A
 * referent looking at "resolved in August, branch Celulares" who taps «Detección de fraude»
 * expects the same slice; resetting to "month so far / every branch" would make the two tabs feel
 * like two screens that happen to sit next to each other.
 *
 * <p>Provided on the reports route, not in root: the filters belong to the reports screen, and
 * coming back to it a week later should start from the current month again, not from whatever was
 * typed last time.
 */
@Injectable()
export class ReportFiltersStore {
  private readonly branches = inject(BranchesService);

  readonly today = todayIso();
  /** Default: the month so far — the question a referent asks most often. */
  readonly from = signal(`${this.today.slice(0, 8)}01`);
  readonly to = signal(this.today);
  readonly branchId = signal<number | null>(null);
  readonly branchOptions = signal<SelectOption[]>([]);

  /** The select speaks strings; empty is the placeholder, which here means "every branch". */
  readonly branchValue = computed(() => {
    const id = this.branchId();
    return id === null ? '' : String(id);
  });

  readonly periodError = computed(() => periodError(this.from(), this.to()));

  constructor() {
    this.branches
      .list()
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (list) =>
          this.branchOptions.set(
            list.map((branch) => ({ value: String(branch.id), label: branch.name })),
          ),
        // Without the catalog the filter still works as "Todos": not worth blocking the screen.
        error: () => this.branchOptions.set([]),
      });
  }

  setBranch(value: string): void {
    this.branchId.set(value === '' ? null : Number(value));
  }

  /**
   * Restores what the URL carried, so a shared link opens on the same period. Anything missing or
   * malformed keeps the default rather than putting a broken value on screen — the URL is typed by
   * hand often enough.
   */
  hydrate(params: ParamMap): void {
    const from = params.get('from');
    const to = params.get('to');
    if (from && to && periodError(from, to) === null) {
      this.from.set(from);
      this.to.set(to);
    }
    const branchId = Number(params.get('branchId'));
    if (Number.isInteger(branchId) && branchId > 0) {
      this.branchId.set(branchId);
    }
  }

  /** What the shell writes back to the URL on every change. */
  asQueryParams(): Params {
    return {
      from: this.from(),
      to: this.to(),
      branchId: this.branchId() ?? undefined,
    };
  }
}
