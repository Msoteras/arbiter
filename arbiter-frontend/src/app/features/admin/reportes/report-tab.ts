import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params } from '@angular/router';
import { Observable, Subscription } from 'rxjs';

import { ReportFile, downloadReport, reportErrorMessage } from './report-download';
import { ReportFiltersStore } from './report-filters.store';
import { rememberReportsTab } from './reports-tab-memory';
import { ReportFormat } from './resolution-report';

/** What every report answers with: the filters it ran under, and the rows it found. */
export interface ReportPayload<TRow> {
  branch: string | null;
  rows: TRow[];
}

/**
 * What the two report tabs do identically: ask for a preview, page through it, export it, and
 * throw it away when a filter changes.
 *
 * <p>It is a base class and not a service because all of it is component state — signals the
 * template reads, a subscription tied to the component's life. The two tabs differ in the report
 * they ask for and the filter they own, which is what the abstract members are.
 *
 * <p>It has no Angular decorator on purpose: it injects with {@link inject} and declares no inputs
 * or lifecycle hooks, so it is a plain class the components extend.
 */
export abstract class ReportTab<TRow, TReport extends ReportPayload<TRow>, TParams> {
  protected readonly filters = inject(ReportFiltersStore);
  protected readonly destroyRef = inject(DestroyRef);
  /** The query params the tab opened with; each tab restores its own filter from them. */
  protected readonly route = inject(ActivatedRoute);
  private readonly document = inject(DOCUMENT);

  protected readonly report = signal<TReport | null>(null);
  protected readonly loading = signal(false);
  protected readonly exporting = signal<ReportFormat | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Kept so a filter change can cancel it: otherwise its response lands under the new filters. */
  private previewRequest: Subscription | null = null;

  protected readonly page = signal(0);
  protected readonly pageSize = signal(20);
  protected readonly rows = computed(() => this.report()?.rows ?? []);
  protected readonly totalPages = computed(() => Math.ceil(this.rows().length / this.pageSize()));
  protected readonly pageRows = computed(() => {
    const start = this.page() * this.pageSize();
    return this.rows().slice(start, start + this.pageSize());
  });

  /** The filters this tab sends, its own filter included. */
  protected abstract params(): TParams;

  /** This tab's own filter as it travels in the URL; the shell writes it with the shared ones. */
  protected abstract tabParams(): Params;

  protected abstract fetchReport(params: TParams): Observable<TReport>;

  protected abstract fetchFile(params: TParams, format: ReportFormat): Observable<ReportFile>;

  /**
   * Called from the subclass constructor, once it has restored its own filter from the URL:
   * wires the URL and the shared filters, and asks for the first preview.
   *
   * <p>Not done in this constructor because it would run before the subclass has read its filter,
   * and the preview would go out without it.
   */
  protected start(): void {
    // Cuál es esta solapa sale de la ruta y no de una constante por tab: la ruta es la que el
    // redirect de `insurer/reports` va a usar después, así que no pueden discrepar.
    rememberReportsTab(this.route.snapshot.routeConfig?.path);

    effect(() => this.filters.tabParams.set(this.tabParams()));
    this.destroyRef.onDestroy(() => this.filters.tabParams.set({}));

    // The shared filters are edited outside this component, so discarding the stale preview has to
    // react to them rather than hang off a setter. The first run is skipped: it only reads the
    // filters the tab opened with, and would cancel the preview a link asked for.
    let firstRun = true;
    effect(() => {
      this.filters.from();
      this.filters.to();
      this.filters.branchId();
      if (firstRun) {
        firstRun = false;
        return;
      }
      this.discardPreview();
    });

    // The screen opens with its report already on it, whether it was reached from the menu, from
    // the other tab or from a shared link: the three show the filters that are on screen, so making
    // only one of them wait for a click is a difference nobody can see and everybody notices.
    this.loadPreview();
  }

  protected loadPreview(): void {
    if (this.filters.periodError()) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.previewRequest?.unsubscribe();
    this.previewRequest = this.fetchReport(this.params())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (report) => {
          this.filters.notePreviewedBranch(report.branch);
          this.report.set(report);
          this.page.set(0);
          this.loading.set(false);
        },
        error: (err: HttpErrorResponse) => {
          this.loading.set(false);
          this.error.set(reportErrorMessage(err));
        },
      });
  }

  protected exportAs(format: ReportFormat): void {
    if (this.filters.periodError() || this.exporting()) {
      return;
    }
    this.exporting.set(format);
    this.error.set(null);
    this.fetchFile(this.params(), format)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ blob, filename }) => {
          downloadReport(this.document, blob, filename);
          this.exporting.set(null);
        },
        error: (err: HttpErrorResponse) => {
          this.exporting.set(null);
          this.error.set(reportErrorMessage(err));
        },
      });
  }

  protected setPageSize(size: number): void {
    this.pageSize.set(size);
    this.page.set(0);
  }

  /** A preview only describes the parameters it ran with; once one changes, it would mislead. */
  protected discardPreview(): void {
    this.previewRequest?.unsubscribe();
    this.previewRequest = null;
    this.loading.set(false);
    this.report.set(null);
    this.error.set(null);
  }
}
