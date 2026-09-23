import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Params, RouterLink } from '@angular/router';
import { EChartsCoreOption } from 'echarts/core';
import { Observable } from 'rxjs';

import { clasificacionLabel, clasificacionTone } from '../../../core/models/clasificacion';
import { estadoLabel, estadoTone } from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { bucketLabel, formatDate, formatDateTime } from '../../../core/util/datetime';
import { staggerReveal } from '../../../shared/animations';
import { RatePipe } from '../../../shared/pipes/rate.pipe';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ChartComponent } from '../../../shared/ui/chart/chart.component';
import { ChartTheme, baseChartOptions, readChartTheme } from '../../../shared/ui/chart/chart-theme';
import {
  DistributionComponent,
  DistributionItem,
} from '../../../shared/ui/distribution/distribution.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { PaginationComponent } from '../../../shared/ui/pagination/pagination.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { StatTileComponent } from '../../../shared/ui/stat-tile/stat-tile.component';
import { TableComponent } from '../../../shared/ui/table/table.component';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { ReportActionsComponent } from './report-actions.component';
import { ReportFile } from './report-download';
import { ReportFiltersComponent } from './report-filters.component';
import { ReportTab } from './report-tab';
import {
  ReportFormat,
  ResolutionReport,
  ResolutionReportParams,
  ResolutionReportRow,
  decisionLabel,
  fastTrackTrend,
  formatDuration,
  resolutionTimeTrend,
  resolvedTrend,
  waitingBreakdown,
} from './resolution-report';
import { ResolutionReportService } from './resolution-report.service';

/** Exports come from reports-service, so the file matches what the API gives an auditor. */
@Component({
  selector: 'app-resolution-report',
  imports: [
    RatePipe,
    RouterLink,
    BadgeComponent,
    CardComponent,
    ChartComponent,
    DistributionComponent,
    EmptyStateComponent,
    InfoTipComponent,
    InlineLoadingComponent,
    PaginationComponent,
    ReportActionsComponent,
    ReportFiltersComponent,
    SelectComponent,
    StatTileComponent,
    TableComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal],
  templateUrl: './resolution-report.component.html',
  styleUrls: ['./report-params.scss', './report-tab.scss', './resolution-report.component.scss'],
})
export class ResolutionReportComponent extends ReportTab<
  ResolutionReportRow,
  ResolutionReport,
  ResolutionReportParams
> {
  private readonly reports = inject(ResolutionReportService);
  private readonly expedientes = inject(ExpedienteService);
  /** Read once: ECharts doesn't understand `var(--status-ok)`. */
  private readonly theme: ChartTheme = readChartTheme();

  protected readonly claimCause = signal('');
  private readonly claimCauseCatalog = signal<SelectOption[]>([]);
  /** Adds the selected cause if missing from the catalog, or the select would read "Todos". */
  protected readonly claimCauseOptions = computed<SelectOption[]>(() => {
    const catalog = this.claimCauseCatalog();
    const selected = this.claimCause();
    return !selected || catalog.some((option) => option.value === selected)
      ? catalog
      : [...catalog, { value: selected, label: selected }];
  });

  /** From the backend, not computed here, so the screen and the exported file always agree. */
  protected readonly summary = computed(() => this.report()?.summary ?? null);

  protected readonly statusItems = computed<DistributionItem[]>(() =>
    (this.summary()?.byStatus ?? []).map((bucket) => ({
      label: estadoLabel(bucket.label),
      count: bucket.count,
      tone: estadoTone(bucket.label),
    })),
  );

  protected readonly claimCauseItems = computed<DistributionItem[]>(() =>
    (this.summary()?.byClaimCause ?? []).map((bucket) => ({
      label: bucket.label,
      count: bucket.count,
      tone: 'neutral' as StatusTone,
    })),
  );

  protected readonly resolvedSub = computed(() => {
    const current = this.report();
    if (!current) {
      return '';
    }
    const { summary, previousSummary } = current;
    const lapsed = summary.totalCases - summary.decidedCases;
    // Only mention lapsed cases when there are some: "0 caducados" is noise.
    const detail = lapsed === 0 ? '' : `${summary.decidedCases} decididos · ${lapsed} caducados`;
    return [resolvedTrend(summary, previousSummary), detail].filter(Boolean).join(' · ');
  });

  protected readonly fastTrackSub = computed(() => {
    const current = this.report();
    if (!current) {
      return '';
    }
    const { summary, previousSummary } = current;
    const detail = `${summary.fastTrackCases} de ${summary.totalCases}`;
    return [detail, fastTrackTrend(summary, previousSummary)].filter(Boolean).join(' · ');
  });

  protected readonly resolutionTimeSub = computed(() => {
    const current = this.report();
    if (!current) {
      return '';
    }
    const { summary, previousSummary } = current;
    return [resolutionTimeTrend(summary, previousSummary), waitingBreakdown(summary)]
      .filter(Boolean)
      .join(' · ');
  });

  /**
   * Line: the card's average (decided cases); bars: volume closed per bucket, so a thin average isn't
   * read as a trend. Buckets with no decisions break the line instead of dropping to zero.
   */
  protected readonly timelineChart = computed<EChartsCoreOption>(() => {
    const current = this.report();
    const points = current?.timeline ?? [];
    const granularity = current?.granularity ?? 'DAY';
    const labels = points.map((point) => bucketLabel(point.bucket, granularity));
    const base = baseChartOptions(this.theme);
    return {
      ...base,
      tooltip: {
        ...(base['tooltip'] as object),
        trigger: 'axis',
        formatter: (params: unknown) => {
          const index = (params as { dataIndex: number }[])[0]?.dataIndex ?? 0;
          const point = points[index];
          if (!point) {
            return '';
          }
          const unit = point.resolved === 1 ? 'expediente cerrado' : 'expedientes cerrados';
          const average =
            point.averageMinutes === null
              ? 'sin decisiones en el tramo'
              : `promedio ${formatDuration(point.averageMinutes)} sobre ${point.decided}`;
          return `${labels[index]}<br>${point.resolved} ${unit}<br>${average}`;
        },
      },
      legend: {
        data: ['Tiempo promedio', 'Expedientes cerrados'],
        bottom: 0,
        textStyle: { color: this.theme.muted },
        icon: 'roundRect',
      },
      grid: { left: 8, right: 8, top: 16, bottom: 44, containLabel: true },
      xAxis: {
        type: 'category',
        data: labels,
        axisLine: { lineStyle: { color: this.theme.grid } },
        axisTick: { show: false },
        axisLabel: { color: this.theme.muted },
      },
      yAxis: [
        {
          type: 'value',
          // Minutes on the axis, labeled like the card ("2 d", "5 h"): forcing days would flatten
          // hour-long Fast Track cases against zero.
          axisLabel: {
            color: this.theme.muted,
            formatter: (value: number) => formatDuration(value),
          },
          splitLine: { lineStyle: { color: this.theme.grid } },
        },
        {
          type: 'value',
          minInterval: 1,
          axisLabel: { color: this.theme.muted },
          splitLine: { show: false },
        },
      ],
      series: [
        {
          name: 'Expedientes cerrados',
          type: 'bar',
          yAxisIndex: 1,
          data: points.map((point) => point.resolved),
          itemStyle: { color: this.theme.ink, opacity: 0.25, borderRadius: [3, 3, 0, 0] },
          barMaxWidth: 28,
        },
        {
          name: 'Tiempo promedio',
          type: 'line',
          yAxisIndex: 0,
          data: points.map((point) => point.averageMinutes),
          symbolSize: 6,
          lineStyle: { width: 2, color: this.theme.status.ok },
          itemStyle: { color: this.theme.status.ok },
        },
      ],
    };
  });

  /** Screen-reader text in place of the canvas. */
  protected readonly timelineDescription = computed(() => {
    const current = this.report();
    const points = current?.timeline ?? [];
    const granularity = current?.granularity ?? 'DAY';
    const parts = points.map((point) => {
      const average =
        point.averageMinutes === null ? 'sin decisiones' : formatDuration(point.averageMinutes);
      return `${bucketLabel(point.bucket, granularity)}: ${point.resolved} cerrados, promedio ${average}`;
    });
    return `Tiempo promedio de resolución a lo largo del período. ${parts.join('. ')}.`;
  });

  protected readonly granularityNote = computed(() => {
    switch (this.report()?.granularity) {
      case 'WEEK':
        return 'Agrupado por semana: con este volumen, el detalle diario sería casi todo ceros';
      case 'MONTH':
        return 'Agrupado por mes';
      default:
        return 'Día a día';
    }
  });

  protected readonly formatDate = formatDate;
  protected readonly formatDateTime = formatDateTime;
  protected readonly formatDuration = formatDuration;
  protected readonly decisionLabel = decisionLabel;
  protected readonly waitingBreakdown = waitingBreakdown;
  protected readonly estadoLabel = estadoLabel;
  protected readonly estadoTone = estadoTone;
  protected readonly clasificacionLabel = clasificacionLabel;
  protected readonly clasificacionTone = clasificacionTone;

  constructor() {
    super();
    this.expedientes
      .claimCauseNames()
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (names) =>
          this.claimCauseCatalog.set(names.map((name) => ({ value: name, label: name }))),
        // Without the catalog the filter still works: claimCauseOptions names the selected one.
        error: () => this.claimCauseCatalog.set([]),
      });

    this.claimCause.set(this.route.snapshot.queryParamMap.get('claimCause')?.trim() ?? '');
    this.start();
  }

  protected setClaimCause(value: string): void {
    this.claimCause.set(value);
    this.discardPreview();
  }

  protected override fetchReport(params: ResolutionReportParams): Observable<ResolutionReport> {
    return this.reports.preview(params);
  }

  protected override fetchFile(
    params: ResolutionReportParams,
    format: ReportFormat,
  ): Observable<ReportFile> {
    return this.reports.export(params, format);
  }

  protected override tabParams(): Params {
    return { claimCause: this.claimCause() || undefined };
  }

  protected override params(): ResolutionReportParams {
    return {
      from: this.filters.from(),
      to: this.filters.to(),
      branchId: this.filters.branchId(),
      claimCause: this.claimCause(),
    };
  }
}
