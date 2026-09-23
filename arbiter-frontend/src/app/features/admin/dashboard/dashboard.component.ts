import { DatePipe, formatNumber } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  LOCALE_ID,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { EChartsCoreOption } from 'echarts/core';
import { switchMap, tap } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { clasificacionLabel, clasificacionTone } from '../../../core/models/clasificacion';
import { estadoLabel, estadoTone } from '../../../core/models/estado';
import { RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { ruleTypeDescription, ruleTypeLabel } from '../../../core/models/rule-type';
import { StatusTone } from '../../../core/models/status-tone';
import { formatRate } from '../../../core/util/percent';
import { formatMoney } from '../../../core/util/money';
import { percentagePoints, trendText } from '../../../core/util/trend';
import { staggerReveal } from '../../../shared/animations';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ChartTheme, baseChartOptions, readChartTheme } from '../../../shared/ui/chart/chart-theme';
import { ChartComponent } from '../../../shared/ui/chart/chart.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import {
  MenuButtonComponent,
  MenuItem,
} from '../../../shared/ui/menu-button/menu-button.component';
import { StatTileComponent } from '../../../shared/ui/stat-tile/stat-tile.component';
import { BranchesService } from '../branches.service';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { AttentionItem, AttentionService } from './attention.service';
import {
  ClaimMetrics,
  MetricCount,
  MetricsFilter,
  MetricsRange,
  TimelinePoint,
  providerLabel,
  resolutionTimeLabel,
} from './claim-metrics';
import { ClaimMetricsService, MetricsPeriod } from './claim-metrics.service';
import {
  DistributionComponent,
  DistributionItem,
} from '../../../shared/ui/distribution/distribution.component';

type PeriodChoice = MetricsRange | 'CUSTOM';

interface FunnelStep {
  label: string;
  value: number;
  note: string;
  tone: StatusTone;
}

interface Kpi {
  label: string;
  value: string;
  sub: string;
  /** Empty when there's nothing to compare against. */
  trend: string;
  /** 0..1 for the card's bar; null where the value isn't a proportion. */
  progress: number | null;
  tone: StatusTone;
}

/** Same tones as app-fraud-gauge. */
const RISK_TONES: Record<RiskBand, StatusTone> = {
  LOW: 'ok',
  MEDIUM: 'warning',
  HIGH: 'risk',
  CRITICAL: 'danger',
};

const ALL = '__todos__';

/**
 * Stricter than the internal goal's tone: missing the legal deadline is a regulatory problem, so any
 * miss leaves green and below 90% is red.
 */
function legalTone(rate: number | null): StatusTone {
  if (rate === null) {
    return 'neutral';
  }
  if (rate >= 1) {
    return 'ok';
  }
  return rate >= 0.9 ? 'warning' : 'danger';
}

@Component({
  selector: 'app-dashboard',
  imports: [
    CardComponent,
    ChartComponent,
    DatePipe,
    EmptyStateComponent,
    InputComponent,
    InlineLoadingComponent,
    MenuButtonComponent,
    RouterLink,
    StatTileComponent,
    DistributionComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent {
  private readonly claimMetrics = inject(ClaimMetricsService);
  private readonly attention = inject(AttentionService);
  private readonly branches = inject(BranchesService);
  private readonly expedientes = inject(ExpedienteService);
  private readonly session = inject(AuthSessionService);
  private readonly locale = inject(LOCALE_ID);
  private readonly theme: ChartTheme = readChartTheme();

  // ─── Controls ──────────────────────────────────────────────────────────────────────

  protected readonly period = signal<PeriodChoice>('MONTH');
  protected readonly customFrom = signal(isoDaysAgo(29));
  protected readonly customTo = signal(isoDaysAgo(0));
  protected readonly branchId = signal<number | null>(null);
  protected readonly analystId = signal<number | null>(null);

  protected readonly periodOptions: MenuItem[] = [
    { value: 'WEEK', label: 'Última semana' },
    { value: 'MONTH', label: 'Últimos 30 días' },
    { value: 'QUARTER', label: 'Últimos 90 días' },
    { value: 'CUSTOM', label: 'Personalizado' },
  ];
  protected readonly periodLabel = computed(
    () => this.periodOptions.find((option) => option.value === this.period())?.label ?? '',
  );
  protected readonly isCustom = computed(() => this.period() === 'CUSTOM');

  protected readonly branchOptions = signal<MenuItem[]>([{ value: ALL, label: 'Todos los ramos' }]);
  protected readonly analystOptions = signal<MenuItem[]>([
    { value: ALL, label: 'Todos los analistas' },
  ]);
  protected readonly branchLabel = computed(() => labelOf(this.branchOptions(), this.branchId()));
  protected readonly analystLabel = computed(() =>
    labelOf(this.analystOptions(), this.analystId()),
  );

  /** Team-management view: referente only, same as cases-service's `analysts/workload`. */
  protected readonly canFilterByAnalyst = computed(
    () => this.session.session()?.rol === 'REFERENTE_ASEGURADORA',
  );

  // ─── Data ──────────────────────────────────────────────────────────────────────────

  /** Kept while the next period loads, so the screen doesn't flicker. */
  protected readonly data = signal<ClaimMetrics | null>(null);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly attentionItems = signal<AttentionItem[]>([]);

  /** A reversed custom range would get a 400 from the backend; it's a typo, so skip the request. */
  private readonly request = computed<{ period: MetricsPeriod; filter: MetricsFilter } | null>(
    () => {
      const filter: MetricsFilter = { branchId: this.branchId(), analystId: this.analystId() };
      const choice = this.period();
      if (choice !== 'CUSTOM') {
        return { period: { range: choice }, filter };
      }
      const from = this.customFrom();
      const to = this.customTo();
      return from && to && from <= to ? { period: { from, to }, filter } : null;
    },
  );

  constructor() {
    toObservable(this.request)
      .pipe(
        tap(() => {
          this.loading.set(true);
          this.failed.set(false);
        }),
        switchMap((request) =>
          request === null ? [] : this.claimMetrics.load(request.period, request.filter),
        ),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (metrics) => {
          this.data.set(metrics);
          this.loading.set(false);
        },
        error: () => {
          this.failed.set(true);
          this.loading.set(false);
        },
      });

    // "Requiere atención" doesn't depend on the period (what's stuck today is stuck today), so it's
    // loaded once, not on every filter change.
    this.attention
      .load()
      .pipe(takeUntilDestroyed())
      .subscribe({ next: (items) => this.attentionItems.set(items), error: () => {} });

    this.branches
      .list()
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (list) =>
          this.branchOptions.set([
            { value: ALL, label: 'Todos los ramos' },
            ...list.map((branch) => ({ value: String(branch.id), label: branch.name })),
          ]),
        error: () => {},
      });

    if (this.canFilterByAnalyst()) {
      this.expedientes
        .analystWorkload()
        .pipe(takeUntilDestroyed())
        .subscribe({
          next: (list) =>
            this.analystOptions.set([
              { value: ALL, label: 'Todos los analistas' },
              ...list.map((analyst) => ({
                value: String(analyst.analystId),
                label: analyst.name,
              })),
            ]),
          error: () => {},
        });
    }
  }

  protected setPeriod(value: string): void {
    this.period.set(value as PeriodChoice);
  }

  protected setBranch(value: string): void {
    this.branchId.set(value === ALL ? null : Number(value));
  }

  protected setAnalyst(value: string): void {
    this.analystId.set(value === ALL ? null : Number(value));
  }

  // ─── Derived state ─────────────────────────────────────────────────────────────────

  protected readonly isEmpty = computed(() => {
    const summary = this.data()?.summary;
    return !!summary && summary.reportedCases === 0 && summary.resolvedCases === 0;
  });

  protected readonly comparisonLabel = computed(() => {
    const metrics = this.data();
    if (!metrics) {
      return '';
    }
    const days = daysBetween(metrics.from, metrics.to);
    const to = shiftDays(metrics.from, -1);
    return `comparado con ${shortDate(shiftDays(to, -(days - 1)))} – ${shortDate(to)}`;
  });

  protected readonly funnel = computed<FunnelStep[]>(() => {
    const metrics = this.data();
    if (!metrics) {
      return [];
    }
    const { funnel } = metrics;
    const share = (value: number) =>
      funnel.reported === 0 ? '' : `${this.percent(value / funnel.reported)} del ingreso`;
    return [
      {
        label: 'Denunciados',
        value: funnel.reported,
        note: this.trendOf(
          funnel.reported,
          metrics.previousSummary.reportedCases,
          'count',
          'neither',
        ),
        tone: 'neutral',
      },
      {
        label: 'Analizados por el modelo',
        value: funnel.analyzed,
        // Smaller than intake by design: the LLM never runs on Fast Track (enforced by a DB CHECK).
        note: share(funnel.analyzed),
        tone: 'info',
      },
      { label: 'Decididos', value: funnel.decided, note: share(funnel.decided), tone: 'ok' },
      {
        label: 'Vía Fast Track',
        value: funnel.fastTrack,
        note: share(funnel.fastTrack),
        tone: 'info',
      },
    ];
  });

  protected readonly stillOpenNote = computed(() => {
    const open = this.data()?.funnel.stillOpen ?? 0;
    if (open === 0) {
      return 'Todo lo que entró en el período ya está cerrado';
    }
    return open === 1
      ? '1 expediente del período sigue abierto'
      : `${open} expedientes del período siguen abiertos`;
  });

  protected readonly kpis = computed<Kpi[]>(() => {
    const metrics = this.data();
    if (!metrics) {
      return [];
    }
    const { summary, previousSummary, agreement } = metrics;
    const decided = summary.approvedCases + summary.rejectedCases;
    const previousDecided = previousSummary.approvedCases + previousSummary.rejectedCases;
    return [
      {
        label: 'Tiempo prom. de resolución',
        value: resolutionTimeLabel(summary.averageResolutionHours),
        sub: this.targetNote(metrics, decided),
        // Share of decided cases within the goal; no bar without a goal.
        progress: targetShare(metrics, decided),
        tone: targetTone(metrics, decided),
        // Taking longer is worse: an upward arrow is bad news here.
        trend: this.trendOf(
          summary.averageResolutionHours,
          previousSummary.averageResolutionHours,
          'hours',
          'down',
          previousDecided,
        ),
      },
      {
        label: 'Plazo legal (art. 56)',
        value: this.percent(metrics.legalDeadline.rate),
        sub:
          metrics.legalDeadline.decided === 0
            ? 'nada decidido en el período'
            : `${metrics.legalDeadline.onTime} de ${metrics.legalDeadline.decided} en término`,
        trend: '',
        progress: metrics.legalDeadline.rate,
        tone: legalTone(metrics.legalDeadline.rate),
      },
      {
        label: 'Coincidencia con el modelo',
        value: this.percent(agreement.rate),
        sub:
          agreement.decided === 0
            ? 'ningún caso con recomendación accionable'
            : `${agreement.agreed} de ${agreement.decided} con recomendación`,
        trend: '',
        progress: agreement.rate,
        tone: 'info' as StatusTone,
      },
      {
        label: 'Reapertura',
        value: this.percent(metrics.reopening.rate),
        sub:
          metrics.reopening.resolved === 0
            ? 'nada cerrado en el período'
            : `${metrics.reopening.reopened} de ${metrics.reopening.resolved} cerrados`,
        trend: '',
        progress: metrics.reopening.rate,
        // Unlike the rest, an empty bar is the good outcome here.
        tone: (metrics.reopening.rate ?? 0) > 0 ? ('warning' as StatusTone) : ('ok' as StatusTone),
      },
      {
        label: 'Aprobación',
        value: this.percent(summary.approvalRate),
        sub:
          decided === 0
            ? 'nada decidido en el período'
            : `${summary.approvedCases} de ${decided} decididos`,
        progress: summary.approvalRate,
        tone: 'ok' as StatusTone,
        trend: this.trendOf(
          summary.approvalRate,
          previousSummary.approvalRate,
          'rate',
          'up',
          previousDecided,
        ),
      },
      {
        label: 'Fast Track',
        value: this.percent(summary.fastTrackRate),
        sub: `${summary.fastTrackCases} de ${summary.reportedCases} denunciados`,
        progress: summary.fastTrackRate,
        tone: 'info' as StatusTone,
        trend: this.trendOf(
          summary.fastTrackRate,
          previousSummary.fastTrackRate,
          'rate',
          'up',
          previousSummary.reportedCases,
        ),
      },
    ];
  });

  /**
   * Says "de gestión" because the goal excludes third-party waits while the headline is total time;
   * without it, a 35-day average against a mostly-met 21-day goal reads as a bug.
   */
  private targetNote(metrics: ClaimMetrics, decided: number): string {
    const target = metrics.resolutionTarget;
    if (!target.enabled || target.targetDays === null || decided === 0) {
      return 'de la denuncia a la decisión';
    }
    if (target.exceeded === 0) {
      return `Objetivo: ${target.targetDays} d de gestión · los ${decided} lo cumplieron`;
    }
    return `Objetivo: ${target.targetDays} d de gestión · ${target.exceeded} de ${decided} lo superaron`;
  }

  /**
   * Shown only from one hour of waiting: below that it's a case passing briefly through a waiting
   * status, and the line would just say "0 h".
   */
  protected readonly waitingBreakdown = computed(() => {
    const summary = this.data()?.summary;
    const total = summary?.averageResolutionHours;
    const waiting = summary?.averageWaitingHours;
    if (total == null || waiting == null || waiting < 1) {
      return '';
    }
    const own = Math.max(total - waiting, 0);
    return `${resolutionTimeLabel(own)} de gestión · ${resolutionTimeLabel(waiting)} esperando a terceros`;
  });

  /** Empty unless both sides exist: one average alone compares with nothing. */
  protected readonly fastTrackComparison = computed(() => {
    const impact = this.data()?.fastTrack;
    if (!impact || impact.fastTrackHours == null || impact.standardHours == null) {
      return '';
    }
    return (
      `Fast Track: ${resolutionTimeLabel(impact.fastTrackHours)} · ` +
      `Resto: ${resolutionTimeLabel(impact.standardHours)}`
    );
  });

  protected readonly fastTrackBase = computed(() => {
    const impact = this.data()?.fastTrack;
    if (!impact) {
      return '';
    }
    return `${impact.fastTrackDecided} y ${impact.standardDecided} expedientes decididos`;
  });

  /** null when nothing was settled: the section is hidden instead of showing a row of zeros. */
  protected readonly money = computed(() => {
    const metrics = this.data();
    if (!metrics || metrics.settled.settlements === 0) {
      return null;
    }
    const settled = metrics.settled;
    const deductions =
      Number(settled.deductible) + Number(settled.installments) + Number(settled.overdue);
    return {
      settlements: settled.settlements,
      total: formatMoney(+settled.settled),
      average: settled.average === null ? '—' : formatMoney(+settled.average),
      claimed: formatMoney(+settled.claimed),
      deductions: formatMoney(deductions),
      deductible: formatMoney(+settled.deductible),
      installments: formatMoney(+settled.installments),
      overdue: formatMoney(+settled.overdue),
      // Only meaningful if EVERY settlement has a claimed amount; otherwise the ratio divides N cases
      // by fewer than N.
      comparable: settled.claimedCases === settled.settlements && Number(settled.claimed) > 0,
      missing: settled.settlements - settled.claimedCases,
      // Over 100% is legitimate (settled by sum insured, not by the claim), so it's phrased as a
      // ratio rather than something implying a 100% cap.
      share:
        Number(settled.claimed) > 0
          ? this.percent(Number(settled.settled) / Number(settled.claimed))
          : '',
    };
  });

  /** null when there was no fraud in the period. */
  protected readonly fraud = computed(() => {
    const fraud = this.data()?.fraud;
    if (!fraud || fraud.fraudDetermined === 0) {
      return null;
    }
    return {
      cases: fraud.fraudDetermined,
      decided: fraud.decided,
      backedByExpert: fraud.backedByExpert,
      notPaid: formatMoney(+fraud.amountNotPaid),
      hasAmount: Number(fraud.amountNotPaid) > 0,
    };
  });

  protected readonly derivations = computed(() =>
    (this.data()?.derivations ?? []).map((row) => ({
      label: providerLabel(row.providerType),
      derived: row.derived,
      pending: row.derived - row.answered,
      average: row.averageHours === null ? '—' : resolutionTimeLabel(row.averageHours),
    })),
  );

  protected readonly blockingRuleItems = computed<DistributionItem[]>(() =>
    (this.data()?.byBlockingRule ?? []).map((count) => {
      const raw = count.label ?? 'Sin identificar';
      return {
        label: ruleTypeLabel(raw),
        count: count.count,
        // A rule blocking is the rule doing its job, not an alert.
        tone: 'neutral' as StatusTone,
        description: ruleTypeDescription(raw),
      };
    }),
  );

  protected readonly statusItems = computed<DistributionItem[]>(() =>
    (this.data()?.byStatus ?? []).map((count) => ({
      label: labelOrEmpty(count, estadoLabel, 'Sin estado'),
      count: count.count,
      tone: count.label === null ? 'neutral' : estadoTone(count.label),
    })),
  );

  protected readonly classificationItems = computed<DistributionItem[]>(() =>
    (this.data()?.byClassification ?? []).map((count) => ({
      label: labelOrEmpty(count, clasificacionLabel, 'Sin clasificar'),
      count: count.count,
      tone: count.label === null ? 'neutral' : clasificacionTone(count.label),
    })),
  );

  protected readonly riskItems = computed<DistributionItem[]>(() =>
    (this.data()?.byRiskBand ?? []).map((count) => ({
      label: labelOrEmpty(count, (raw) => riskBandLabel(raw as RiskBand), 'Sin evaluar'),
      count: count.count,
      tone: count.label === null ? 'neutral' : (RISK_TONES[count.label as RiskBand] ?? 'neutral'),
    })),
  );

  protected readonly branchItems = computed<DistributionItem[]>(() =>
    (this.data()?.byBranch ?? []).map((count) => ({
      label: count.label ?? 'Sin ramo',
      count: count.count,
      tone: 'neutral' as StatusTone,
    })),
  );

  /** Intake in ink (volume), resolutions in the same teal the app uses for an approved case. */
  protected readonly timelineChart = computed<EChartsCoreOption>(() => {
    const points = this.data()?.timeline ?? [];
    const base = baseChartOptions(this.theme);
    return {
      ...base,
      tooltip: { ...(base['tooltip'] as object), trigger: 'axis' },
      legend: {
        data: ['Denunciados', 'Resueltos'],
        bottom: 0,
        textStyle: { color: this.theme.muted },
        icon: 'roundRect',
      },
      grid: { left: 8, right: 16, top: 16, bottom: 44, containLabel: true },
      xAxis: {
        type: 'category',
        data: points.map((point) => this.bucketLabel(point)),
        axisLine: { lineStyle: { color: this.theme.grid } },
        axisTick: { show: false },
        axisLabel: { color: this.theme.muted },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: this.theme.grid } },
        axisLabel: { color: this.theme.muted },
      },
      series: [
        {
          name: 'Denunciados',
          type: 'bar',
          data: points.map((point) => point.reported),
          itemStyle: { color: this.theme.ink, borderRadius: [3, 3, 0, 0] },
          barMaxWidth: 28,
        },
        {
          name: 'Resueltos',
          type: 'bar',
          data: points.map((point) => point.resolved),
          itemStyle: { color: this.theme.status.ok, borderRadius: [3, 3, 0, 0] },
          barMaxWidth: 28,
        },
      ],
    };
  });

  protected readonly timelineDescription = computed(() => {
    const points = this.data()?.timeline ?? [];
    const parts = points.map(
      (point) =>
        `${this.bucketLabel(point)}: ${point.reported} denunciados, ${point.resolved} resueltos`,
    );
    return `Altas y resoluciones a lo largo del período. ${parts.join('. ')}.`;
  });

  protected readonly granularityNote = computed(() => {
    switch (this.data()?.granularity) {
      case 'WEEK':
        return 'Agrupado por semana: con este volumen, el detalle diario sería casi todo ceros';
      case 'MONTH':
        return 'Agrupado por mes';
      default:
        return 'Día a día';
    }
  });

  // ─── Formatting ────────────────────────────────────────────────────────────────────

  private percent(rate: number | null | undefined): string {
    return formatRate(rate);
  }

  /**
   * Only picks the magnitude format; the trend rules live in {@link trendText}. Rates change in
   * percentage points: 33% to 50% is "+17 pp", not "+17%" (that would be relative).
   */
  private trendOf(
    current: number | null,
    previous: number | null,
    unit: 'count' | 'rate' | 'hours',
    good: 'up' | 'down' | 'neither',
    base = Number.POSITIVE_INFINITY,
  ): string {
    const format = (size: number) =>
      unit === 'rate'
        ? percentagePoints(size)
        : unit === 'hours'
          ? resolutionTimeLabel(size)
          : formatNumber(size, this.locale, '1.0-0');
    return trendText({ current, previous, format, good, base });
  }

  /** "14/06" or "jun 2026". */
  private bucketLabel(point: TimelinePoint): string {
    const date = new Date(`${point.bucket}T00:00:00`);
    if (this.data()?.granularity === 'MONTH') {
      return date.toLocaleDateString('es-AR', { month: 'short', year: 'numeric' });
    }
    return date.toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit' });
  }
}

/** null without a goal: there's no bar to draw. */
function targetShare(metrics: ClaimMetrics, decided: number): number | null {
  const target = metrics.resolutionTarget;
  if (!target.enabled || target.targetDays === null || decided === 0) {
    return null;
  }
  return (decided - target.exceeded) / decided;
}

/** Display thresholds, not business ones: the exact number is shown alongside. */
function targetTone(metrics: ClaimMetrics, decided: number): StatusTone {
  const share = targetShare(metrics, decided);
  if (share === null) {
    return 'neutral';
  }
  if (share >= 0.75) {
    return 'ok';
  }
  return share >= 0.5 ? 'warning' : 'danger';
}

function labelOf(options: MenuItem[], id: number | null): string {
  return options.find((option) => option.value === (id === null ? ALL : String(id)))?.label ?? '';
}

function labelOrEmpty(count: MetricCount, label: (raw: string) => string, empty: string): string {
  return count.label === null ? empty : label(count.label);
}

/** ISO date N days ago, as a date input expects. */
function isoDaysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString().slice(0, 10);
}

function shiftDays(iso: string, days: number): string {
  const date = new Date(`${iso}T00:00:00`);
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
}

function daysBetween(fromIso: string, toIso: string): number {
  const from = new Date(`${fromIso}T00:00:00`).getTime();
  const to = new Date(`${toIso}T00:00:00`).getTime();
  return Math.round((to - from) / 86_400_000) + 1;
}

function shortDate(iso: string): string {
  const [year, month, day] = iso.split('-');
  return `${day}/${month}/${year.slice(2)}`;
}
