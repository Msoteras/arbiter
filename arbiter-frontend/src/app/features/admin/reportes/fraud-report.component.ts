import { formatNumber } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  LOCALE_ID,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Params, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';

import { estadoLabel, estadoTone } from '../../../core/models/estado';
import { RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDate, formatDateTime } from '../../../core/util/datetime';
import { formatRate } from '../../../core/util/percent';
import { trendText } from '../../../core/util/trend';
import { staggerReveal } from '../../../shared/animations';
import { RatePipe } from '../../../shared/pipes/rate.pipe';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import {
  DistributionComponent,
  DistributionItem,
} from '../../../shared/ui/distribution/distribution.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { FraudGaugeComponent } from '../../../shared/ui/fraud-gauge/fraud-gauge.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { PaginationComponent } from '../../../shared/ui/pagination/pagination.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { StatTileComponent } from '../../../shared/ui/stat-tile/stat-tile.component';
import { TableComponent } from '../../../shared/ui/table/table.component';
import {
  FraudReport,
  FraudReportParams,
  FraudReportRow,
  alertEmptyLabel,
  alertLevelLabel,
  fraudSignalLabel,
  indicators,
  riskGaugeBand,
} from './fraud-report';
import { FraudReportService } from './fraud-report.service';
import { ReportActionsComponent } from './report-actions.component';
import { ReportFile } from './report-download';
import { ReportFiltersComponent } from './report-filters.component';
import { ReportTab } from './report-tab';
import { ReportFormat } from './resolution-report';

/** Only the two alerting bands carry color; "not flagged" and "not scored" stay grey. */
const ALERT_TONES: Record<string, StatusTone> = {
  CRITICAL: 'danger',
  HIGH: 'risk',
  NOT_FLAGGED: 'neutral',
  NOT_SCORED: 'neutral',
};

/** The only alerting bands, and so the only ones the filter offers. */
const ALERT_BANDS: RiskBand[] = ['HIGH', 'CRITICAL'];

@Component({
  selector: 'app-fraud-report',
  imports: [
    RatePipe,
    RouterLink,
    BadgeComponent,
    CardComponent,
    DistributionComponent,
    EmptyStateComponent,
    FraudGaugeComponent,
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
  templateUrl: './fraud-report.component.html',
  styleUrls: ['./report-params.scss', './report-tab.scss', './fraud-report.component.scss'],
})
export class FraudReportComponent extends ReportTab<
  FraudReportRow,
  FraudReport,
  FraudReportParams
> {
  private readonly reports = inject(FraudReportService);
  private readonly locale = inject(LOCALE_ID);

  protected readonly riskBand = signal('');
  protected readonly riskBandOptions: SelectOption[] = ALERT_BANDS.map((band) => ({
    value: band,
    label: riskBandLabel(band),
  }));

  /** From the backend, not summed here, so the header and the table describe the same set. */
  protected readonly summary = computed(() => this.report()?.summary ?? null);

  private readonly previousSummary = computed(() => this.report()?.previousSummary ?? null);

  /** Volume only: no better/worse verdict. */
  protected readonly totalClaimsTrend = computed(() => {
    const summary = this.summary();
    const previous = this.previousSummary();
    if (!summary || !previous) {
      return '';
    }
    return trendText({
      current: summary.totalClaims,
      previous: previous.totalClaims,
      format: (size) => formatNumber(size, this.locale, '1.0-0'),
      good: 'neither',
    });
  });

  /** No verdict: a higher share may mean better detection or more suspicious claims. */
  protected readonly flaggedRateTrend = computed(() => {
    const summary = this.summary();
    const previous = this.previousSummary();
    if (!summary || !previous) {
      return '';
    }
    return trendText({
      current: summary.flaggedRate,
      previous: previous.flaggedRate,
      format: formatRate,
      good: 'neither',
      base: previous.totalClaims,
    });
  });

  /** No verdict, same as {@link flaggedRateTrend}. */
  protected readonly multiSignalTrend = computed(() => {
    const summary = this.summary();
    const previous = this.previousSummary();
    if (!summary || !previous) {
      return '';
    }
    return trendText({
      current: summary.multiSignal,
      previous: previous.multiSignal,
      format: (size) => formatNumber(size, this.locale, '1.0-0'),
      good: 'neither',
      base: previous.totalClaims,
    });
  });

  // "Fraude determinado" has no trend on purpose: it lags (see FraudSummary.fraudRate), and the
  // difference between two under-counts isn't a trend.

  protected readonly alertItems = computed<DistributionItem[]>(() =>
    (this.summary()?.byAlertLevel ?? []).map((bucket) => ({
      label: alertLevelLabel(bucket.label),
      count: bucket.count,
      tone: ALERT_TONES[bucket.label ?? ''] ?? 'neutral',
    })),
  );

  /**
   * Neutral tone: a signal is a reason, not a level. Buckets overlap, so shares are over flagged
   * cases, not over the sum of buckets.
   */
  protected readonly signalItems = computed<DistributionItem[]>(() =>
    (this.summary()?.bySignal ?? []).map((bucket) => ({
      label: fraudSignalLabel(bucket.label ?? ''),
      count: bucket.count,
      tone: 'neutral' as StatusTone,
    })),
  );

  protected readonly formatDate = formatDate;
  protected readonly formatDateTime = formatDateTime;
  protected readonly riskBandLabel = riskBandLabel;
  protected readonly estadoLabel = estadoLabel;
  protected readonly estadoTone = estadoTone;
  protected readonly riskGaugeBand = riskGaugeBand;
  protected readonly alertEmptyLabel = alertEmptyLabel;
  protected readonly indicators = indicators;

  constructor() {
    super();
    // Anything the filter doesn't offer (hand-typed link) means every band.
    const band = this.route.snapshot.queryParamMap.get('riskBand') as RiskBand | null;
    this.riskBand.set(band !== null && ALERT_BANDS.includes(band) ? band : '');
    this.start();
  }

  protected setRiskBand(value: string): void {
    this.riskBand.set(value);
    this.discardPreview();
  }

  protected override fetchReport(params: FraudReportParams): Observable<FraudReport> {
    return this.reports.report(params);
  }

  protected override fetchFile(
    params: FraudReportParams,
    format: ReportFormat,
  ): Observable<ReportFile> {
    return this.reports.export(params, format);
  }

  protected override tabParams(): Params {
    return { riskBand: this.riskBand() || undefined };
  }

  protected override params(): FraudReportParams {
    return {
      from: this.filters.from(),
      to: this.filters.to(),
      branchId: this.filters.branchId(),
      riskBand: this.riskBand(),
    };
  }
}
