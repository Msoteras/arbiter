import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Params, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';

import { estadoLabel, estadoTone } from '../../../core/models/estado';
import { RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDate, formatDateTime } from '../../../core/util/datetime';
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
import { ReportFiltersStore } from './report-filters.store';
import { ReportTab } from './report-tab';
import { ReportFormat } from './resolution-report';

/**
 * Alert level → traffic light. Only the two bands that alert carry color; the other two buckets
 * are not alert levels (the score didn't flag the case, or never ran) and stay grey.
 */
const ALERT_TONES: Record<string, StatusTone> = {
  CRITICAL: 'danger',
  HIGH: 'risk',
  NOT_FLAGGED: 'neutral',
  NOT_SCORED: 'neutral',
};

/** The only two bands that are an alert, and so the only ones the filter offers. */
const ALERT_BANDS: RiskBand[] = ['HIGH', 'CRITICAL'];

/**
 * Fraud report: the claims filed in the period that carry at least one signal — a high risk score,
 * the same insured claiming more than once in the trailing year, or a forensic finding on the
 * images. The alert level is the engine's risk band, the same one the case detail's gauge shows.
 *
 * <p>Period and branch come from {@link ReportFiltersStore} (shared with the resolution tab); the
 * risk band is this report's own filter.
 */
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

  protected readonly riskBand = signal('');
  /** No Low or Medium: filtering a fraud report by "alert = Low" means nothing. */
  protected readonly riskBandOptions: SelectOption[] = ALERT_BANDS.map((band) => ({
    value: band,
    label: riskBandLabel(band),
  }));

  /** From the backend, not added up here: the head and the table must describe the same set. */
  protected readonly summary = computed(() => this.report()?.summary ?? null);

  /** The alert level does communicate state: same traffic light as each row's gauge. */
  protected readonly alertItems = computed<DistributionItem[]>(() =>
    (this.summary()?.byAlertLevel ?? []).map((bucket) => ({
      label: alertLevelLabel(bucket.label),
      count: bucket.count,
      tone: ALERT_TONES[bucket.label ?? ''] ?? 'neutral',
    })),
  );

  /**
   * Which signal fired, neutral like the claim cause: a signal is a reason, not a level. The
   * buckets overlap (a case with two signals counts in both), so the shares are over the flagged
   * cases and not over the sum of the buckets — the distribution's own share would read 100% split.
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
    // Only a band the filter offers: anything else in a hand-typed link reads as every band.
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
