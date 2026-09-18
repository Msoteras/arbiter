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
 * <p>Provided by ReportsComponent, not in root: the filters belong to the reports screen, and
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
  /**
   * The active tab's own filter (claim cause, alert level), as it goes in the URL. Held here so the
   * shell is the only one writing the URL: two writers navigating in the same tick overwrite each
   * other's params.
   */
  readonly tabParams = signal<Params>({});

  /** Null until rules-service answers, and for good if it doesn't. */
  private readonly branchCatalog = signal<SelectOption[] | null>(null);
  /** The branch's name as the backend resolved it in the last preview, for when there's no catalog. */
  private readonly previewedBranch = signal<{ id: number; name: string } | null>(null);

  /**
   * The catalog, plus the selected branch if the catalog doesn't have it — which only happens when
   * the catalog couldn't be read. Without that option the select would show "Todos" while every
   * request goes out filtered by the branch the link carried.
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
        next: (list) => {
          this.branchCatalog.set(
            list.map((branch) => ({ value: String(branch.id), label: branch.name })),
          );
          // A link to a branch that no longer exists: back to every branch rather than filtering by
          // something the select can't name.
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

  /** What the backend called the filtered branch, so the select can name it with no catalog. */
  notePreviewedBranch(name: string | null): void {
    const id = this.branchId();
    this.previewedBranch.set(id !== null && name !== null ? { id, name } : null);
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
    const catalog = this.branchCatalog();
    if (
      Number.isInteger(branchId) &&
      branchId > 0 &&
      (catalog === null || catalog.some((option) => option.value === String(branchId)))
    ) {
      this.branchId.set(branchId);
    }
  }

  /**
   * Whether the URL describes a report to show: a valid period that is the one on screen. A tab
   * opened from such a link previews right away instead of waiting for a click.
   */
  describedBy(params: ParamMap): boolean {
    return (
      params.get('from') === this.from() && params.get('to') === this.to() && !this.periodError()
    );
  }

  /** The shared filters only — what a tab link carries to the other tab. */
  sharedQueryParams(): Params {
    return {
      from: this.from(),
      to: this.to(),
      branchId: this.branchId() ?? undefined,
    };
  }

  /** What the shell writes back to the URL on every change. */
  asQueryParams(): Params {
    // Unset filters dropped here: navigateByUrl over a UrlTree writes an undefined value as the
    // literal "undefined".
    return Object.fromEntries(
      Object.entries({ ...this.sharedQueryParams(), ...this.tabParams() }).filter(
        ([, value]) => value !== undefined,
      ),
    );
  }
}
