import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { estadoLabel, estadoTone } from '../../../core/models/estado';
import { RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDateTime } from '../../../core/util/datetime';
import { staggerReveal } from '../../../shared/animations';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
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
  alertEmptyLabel,
  alertLevelLabel,
  indicators,
  riskGaugeBand,
} from './fraud-report';
import { FraudReportService } from './fraud-report.service';
import { downloadReport, reportErrorMessage } from './report-download';
import { ReportFiltersComponent } from './report-filters.component';
import { ReportFiltersStore } from './report-filters.store';
import { ReportFormat } from './resolution-report';

/**
 * Nivel de alerta → semáforo. Solo las dos bandas que alertan llevan color; las otras dos no son
 * niveles de alerta (el score no marcó el expediente, o nunca corrió) y van en gris.
 */
const ALERT_TONES: Record<string, StatusTone> = {
  CRITICAL: 'danger',
  HIGH: 'risk',
  NOT_FLAGGED: 'neutral',
  NOT_SCORED: 'neutral',
};

/** Las dos únicas bandas que son una alerta, y por eso las únicas que ofrece el filtro. */
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
    RouterLink,
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    DistributionComponent,
    EmptyStateComponent,
    FraudGaugeComponent,
    InfoTipComponent,
    InlineLoadingComponent,
    PaginationComponent,
    ReportFiltersComponent,
    SelectComponent,
    StatTileComponent,
    TableComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal],
  templateUrl: './fraud-report.component.html',
  styleUrls: ['./report-params.scss', './fraud-report.component.scss'],
})
export class FraudReportComponent {
  private readonly reports = inject(FraudReportService);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly filters = inject(ReportFiltersStore);

  protected readonly riskBand = signal('');
  /** Sin Bajo ni Medio: filtrar un reporte de fraude por "alerta = Bajo" no significa nada. */
  protected readonly riskBandOptions: SelectOption[] = ALERT_BANDS.map((band) => ({
    value: band,
    label: riskBandLabel(band),
  }));

  protected readonly report = signal<FraudReport | null>(null);
  protected readonly loading = signal(false);
  protected readonly exporting = signal<ReportFormat | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly page = signal(0);
  protected readonly pageSize = signal(20);
  protected readonly rows = computed(() => this.report()?.rows ?? []);
  protected readonly totalPages = computed(() => Math.ceil(this.rows().length / this.pageSize()));
  protected readonly pageRows = computed(() => {
    const start = this.page() * this.pageSize();
    return this.rows().slice(start, start + this.pageSize());
  });
  /** Del backend y no sumado acá: la cabecera y la tabla tienen que describir el mismo conjunto. */
  protected readonly summary = computed(() => this.report()?.summary ?? null);

  /** El nivel de alerta sí comunica estado: mismo semáforo que el gauge de cada fila. */
  protected readonly alertItems = computed<DistributionItem[]>(() =>
    (this.summary()?.byAlertLevel ?? []).map((bucket) => ({
      label: alertLevelLabel(bucket.label),
      count: bucket.count,
      tone: ALERT_TONES[bucket.label ?? ''] ?? 'neutral',
    })),
  );

  protected readonly formatDateTime = formatDateTime;
  protected readonly estadoLabel = estadoLabel;
  protected readonly estadoTone = estadoTone;
  protected readonly riskGaugeBand = riskGaugeBand;
  protected readonly alertEmptyLabel = alertEmptyLabel;
  protected readonly indicators = indicators;

  constructor() {
    // Igual que el tab de resolución: los filtros compartidos se editan afuera, así que la vista
    // previa vieja se descarta reaccionando a ellos y no desde un setter.
    effect(() => {
      this.filters.from();
      this.filters.to();
      this.filters.branchId();
      this.discardPreview();
    });
  }

  protected setRiskBand(value: string): void {
    this.riskBand.set(value);
    this.discardPreview();
  }

  protected loadPreview(): void {
    if (this.filters.periodError()) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.reports
      .report(this.params())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (report) => {
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
    this.reports
      .export(this.params(), format)
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

  private params(): FraudReportParams {
    return {
      from: this.filters.from(),
      to: this.filters.to(),
      branchId: this.filters.branchId(),
      riskBand: this.riskBand(),
    };
  }

  /** Una vista previa solo describe los parámetros con los que corrió; si cambia uno, engaña. */
  private discardPreview(): void {
    this.report.set(null);
    this.error.set(null);
  }
}
