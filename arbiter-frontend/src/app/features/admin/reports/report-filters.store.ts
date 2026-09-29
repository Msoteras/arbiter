import { Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ParamMap, Params } from '@angular/router';

import { todayIso } from '../../../core/util/datetime';
import { SelectOption } from '../../../shared/ui/select/select.component';
import { BranchesService } from '../branches.service';
import { periodError } from './resolution-report';

/**
 * Period and branch filters shared by both reports, so they survive switching tabs. Provided by
 * ReportsComponent, not in root: re-entering the screen starts from the current month again.
 */
@Injectable()
export class ReportFiltersStore {
  private readonly branches = inject(BranchesService);

  readonly today = todayIso();
  /** Default: the month so far. */
  readonly from = signal(`${this.today.slice(0, 8)}01`);
  readonly to = signal(this.today);
  readonly branchId = signal<number | null>(null);
  /**
   * The active tab's own filter, as it goes in the URL. Held here so the shell is the only URL
   * writer: two writers navigating in the same tick overwrite each other's params.
   */
  readonly tabParams = signal<Params>({});

  /** null until rules-service answers, and for good if it doesn't. */
  private readonly branchCatalog = signal<SelectOption[] | null>(null);
  /** Branch name resolved by the backend in the last preview, for when there's no catalog. */
  private readonly previewedBranch = signal<{ id: number; name: string } | null>(null);

  /**
   * Adds the selected branch when the catalog couldn't be read; otherwise the select would show
   * "Todos" while requests go out filtered by the branch in the link.
   */
  readonly branchOptions = computed<SelectOption[]>(() => {
    const catalog = this.branchCatalog() ?? [];
    const id = this.branchId();
    if (id === null || catalog.some((option) => option.value === String(id))) {
      return catalog;
    }
    const previewed = this.previewedBranch();
    const label = previewed?.id === id ? previewed.name : `Ramo ${id}`;
    return [...catalog, { value: String(id), label }];
  });

  /** Empty string is the placeholder, meaning "every branch". */
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
        next: (list) => {
          this.branchCatalog.set(
            list.map((branch) => ({ value: String(branch.id), label: branch.name })),
          );
          // Link to a branch that no longer exists: fall back to every branch.
          const id = this.branchId();
          if (id !== null && !list.some((branch) => branch.id === id)) {
            this.branchId.set(null);
          }
        },
        // Without the catalog the filter still works; branchOptions names the selected one.
        error: () => this.branchCatalog.set(null),
      });
  }

  setBranch(value: string): void {
    this.branchId.set(value === '' ? null : Number(value));
  }

  notePreviewedBranch(name: string | null): void {
    const id = this.branchId();
    this.previewedBranch.set(id !== null && name !== null ? { id, name } : null);
  }

  /** Restores filters from the URL; anything missing or malformed keeps the default. */
  hydrate(params: ParamMap): void {
    const from = params.get('from');
    const to = params.get('to');
    if (from && to && periodError(from, to) === null) {
      this.from.set(from);
      this.to.set(to);
    }
    const branchId = Number(params.get('branchId'));
    const catalog = this.branchCatalog();
    if (
      Number.isInteger(branchId) &&
      branchId > 0 &&
      (catalog === null || catalog.some((option) => option.value === String(branchId)))
    ) {
      this.branchId.set(branchId);
    }
  }

  /** Shared filters only: what a tab link carries to the other tab. */
  sharedQueryParams(): Params {
    return {
      from: this.from(),
      to: this.to(),
      branchId: this.branchId() ?? undefined,
    };
  }

  asQueryParams(): Params {
    // navigateByUrl over a UrlTree writes an undefined value as the literal "undefined".
    return Object.fromEntries(
      Object.entries({ ...this.sharedQueryParams(), ...this.tabParams() }).filter(
        ([, value]) => value !== undefined,
      ),
    );
  }
}
