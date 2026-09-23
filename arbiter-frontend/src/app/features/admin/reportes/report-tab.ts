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

export interface ReportPayload<TRow> {
  branch: string | null;
  rows: TRow[];
}

/**
 * Shared preview/paging/export behaviour of both report tabs. A base class rather than a service
 * because it's all component state. No Angular decorator on purpose: it declares no inputs or hooks.
 */
export abstract class ReportTab<TRow, TReport extends ReportPayload<TRow>, TParams> {
  protected readonly filters = inject(ReportFiltersStore);
  protected readonly destroyRef = inject(DestroyRef);
  protected readonly route = inject(ActivatedRoute);
  private readonly document = inject(DOCUMENT);

  protected readonly report = signal<TReport | null>(null);
  protected readonly loading = signal(false);
  protected readonly exporting = signal<ReportFormat | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Kept so a filter change can cancel it; otherwise its response lands under the new filters. */
  private previewRequest: Subscription | null = null;

  protected readonly page = signal(0);
  protected readonly pageSize = signal(20);
  protected readonly rows = computed(() => this.report()?.rows ?? []);
  protected readonly totalPages = computed(() => Math.ceil(this.rows().length / this.pageSize()));
  protected readonly pageRows = computed(() => {
    const start = this.page() * this.pageSize();
    return this.rows().slice(start, start + this.pageSize());
  });

  protected abstract params(): TParams;

  /** Written to the URL by the shell, together with the shared filters. */
  protected abstract tabParams(): Params;

  protected abstract fetchReport(params: TParams): Observable<TReport>;

  protected abstract fetchFile(params: TParams, format: ReportFormat): Observable<ReportFile>;

  /**
   * Called from the subclass constructor after it restores its own filter from the URL; doing it in
   * this constructor would send the first preview without that filter.
   */
  protected start(): void {
    // Taken from the route, not a per-tab constant: it's what the `insurer/reports` redirect uses.
    rememberReportsTab(this.route.snapshot.routeConfig?.path);

    effect(() => this.filters.tabParams.set(this.tabParams()));
    this.destroyRef.onDestroy(() => this.filters.tabParams.set({}));

    // Shared filters are edited outside this component, so react to them. The first run is skipped:
    // it would cancel the preview a link asked for.
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

  /** A preview only describes the parameters it ran with; after a change it would mislead. */
  protected discardPreview(): void {
    this.previewRequest?.unsubscribe();
    this.previewRequest = null;
    this.loading.set(false);
    this.report.set(null);
    this.error.set(null);
  }
}
