import { DatePipe, formatNumber, formatPercent } from '@angular/common';
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
import { StatusTone } from '../../../core/models/status-tone';
import { staggerReveal } from '../../../shared/animations';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ChartTheme, baseChartOptions, readChartTheme } from '../../../shared/ui/chart/chart-theme';
import { ChartComponent } from '../../../shared/ui/chart/chart.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { MenuButtonComponent, MenuItem } from '../../../shared/ui/menu-button/menu-button.component';
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
  delta,
  resolutionTimeLabel,
} from './claim-metrics';
import { ClaimMetricsService, MetricsPeriod } from './claim-metrics.service';
import { DistributionComponent, DistributionItem } from './distribution.component';

/** Lo que el selector de período ofrece: los tres atajos del backend, más el rango a medida. */
type PeriodChoice = MetricsRange | 'CUSTOM';

/** Un paso del embudo: cuántos llegaron hasta acá y qué parte del ingreso son. */
interface FunnelStep {
  label: string;
  value: number;
  note: string;
  tone: StatusTone;
}

/** Un indicador de la fila de tarjetas, con su variación ya resuelta. */
interface Kpi {
  label: string;
  value: string;
  sub: string;
  /** Texto de la variación, ya con su flecha. Vacío cuando no hay con qué comparar. */
  trend: string;
  /** Proporción de 0 a 1 para la barra de la tarjeta; null donde el valor no es una proporción. */
  progress: number | null;
  tone: StatusTone;
}

/** Tono del semáforo por banda de riesgo, igual que el app-fraud-gauge del expediente. */
const RISK_TONES: Record<RiskBand, StatusTone> = {
  LOW: 'ok',
  MEDIUM: 'warning',
  HIGH: 'risk',
  CRITICAL: 'danger',
};

const ALL = '__todos__';

/**
 * Cuántos expedientes decididos tiene que haber tenido el período anterior para que comparar contra
 * él signifique algo. Con menos, un solo caso mueve el promedio decenas de días y la flecha anuncia
 * un derrumbe que es apenas ruido — el tipo de número que termina citado como un hecho.
 */
const MIN_COMPARISON_BASE = 5;

/**
 * Tablero de gestión del referente: cómo viene operando su propia cartera de siniestros.
 *
 * Los datos son de la aseguradora del usuario autenticado y de ninguna otra — la compañía no viaja
 * como parámetro, la resuelve reports-service a partir del token.
 *
 * La pantalla se lee de arriba hacia abajo como una pregunta que se va achicando: qué entró y hasta
 * dónde llegó (el embudo), qué tan bien se procesó (los indicadores), cómo se movió en el tiempo, y
 * finalmente qué hay que hacer hoy (el panel de atención). Ese último es el que convierte el
 * tablero en herramienta de trabajo en vez de un resumen que se mira una vez por mes.
 */
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

  // ─── Controles ─────────────────────────────────────────────────────────────────────

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
  protected readonly analystLabel = computed(() => labelOf(this.analystOptions(), this.analystId()));

  /**
   * El recorte por analista es una vista de gestión del equipo, así que sólo la ve el referente —
   * el mismo criterio con el que cases-service reserva `analysts/workload` para ese rol. Un
   * analista mirando este tablero no tiene a quién filtrar más que a sí mismo.
   */
  protected readonly canFilterByAnalyst = computed(
    () => this.session.session()?.rol === 'REFERENTE_ASEGURADORA',
  );

  // ─── Datos ─────────────────────────────────────────────────────────────────────────

  /** El período se mantiene mientras carga el siguiente, para que la pantalla no parpadee. */
  protected readonly data = signal<ClaimMetrics | null>(null);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly attentionItems = signal<AttentionItem[]>([]);

  /**
   * Un período a medida al revés (desde después de hasta) lo rechaza el backend con un 400. Se
   * corta acá antes de pedirlo: es un error de tipeo, no vale una ida y vuelta ni un cartel rojo.
   */
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

    // "Requiere atención" no depende del período: lo que está frenado hoy, está frenado hoy,
    // aunque se mire el trimestre pasado. Por eso se pide una sola vez y no con cada filtro.
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

  // ─── Lecturas derivadas ────────────────────────────────────────────────────────────

  /** Nada denunciado y nada resuelto: los gráficos vacíos no dicen más que una línea de texto. */
  protected readonly isEmpty = computed(() => {
    const summary = this.data()?.summary;
    return !!summary && summary.reportedCases === 0 && summary.resolvedCases === 0;
  });

  /** El período con el que se compara, para que el encabezado no lo deje implícito. */
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
        note: this.trendOf(funnel.reported, metrics.previousSummary.reportedCases, 'count', 'neither'),
        // La entrada del embudo es volumen, no estado: tinta.
        tone: 'neutral',
      },
      {
        label: 'Analizados por el modelo',
        value: funnel.analyzed,
        // Este paso es menor que el ingreso por diseño y no por una falla de cobertura: el Fast
        // Track lo decide el motor de reglas y el modelo nunca corre (la base lo prohíbe con un
        // CHECK explícito). No se aclara en pantalla — el paso de Fast Track está justo al lado y
        // la resta se explica sola.
        note: share(funnel.analyzed),
        // Mismo azul con el que la app pinta todo lo que viene del modelo.
        tone: 'info',
      },
      { label: 'Decididos', value: funnel.decided, note: share(funnel.decided), tone: 'ok' },
      // Fast Track es una clasificación, y en toda la app se pinta con el azul de clasificacionTone.
      { label: 'Vía Fast Track', value: funnel.fastTrack, note: share(funnel.fastTrack), tone: 'info' },
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
        // Con objetivo, la barra es qué parte de lo decidido lo cumplió. Sin objetivo no hay barra:
        // una duración suelta no es una proporción y no tendría contra qué medirse.
        progress: targetShare(metrics, decided),
        // El color lo decide el cumplimiento, no el número: quedarse dentro del objetivo es la
        // lectura buena, pasarse es la que hay que mirar.
        tone: targetTone(metrics, decided),
        // Tardar más es peor: la flecha para arriba acá es mala noticia.
        trend: this.trendOf(
          summary.averageResolutionHours,
          previousSummary.averageResolutionHours,
          'hours',
          'down',
          previousDecided,
        ),
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
        label: 'Aprobación',
        value: this.percent(summary.approvalRate),
        sub: decided === 0 ? 'nada decidido en el período' : `${summary.approvedCases} de ${decided} decididos`,
        progress: summary.approvalRate,
        tone: 'ok' as StatusTone,
        trend: this.trendOf(
          summary.approvalRate, previousSummary.approvalRate, 'rate', 'up', previousDecided),
      },
      {
        label: 'Fast Track',
        value: this.percent(summary.fastTrackRate),
        sub: `${summary.fastTrackCases} de ${summary.reportedCases} denunciados`,
        progress: summary.fastTrackRate,
        tone: 'info' as StatusTone,
        trend: this.trendOf(
          summary.fastTrackRate, previousSummary.fastTrackRate, 'rate', 'up',
          previousSummary.reportedCases),
      },
    ];
  });

  /**
   * La línea bajo el tiempo promedio: cuántos de los decididos se pasaron del objetivo. Sin
   * objetivo fijado dice qué mide el número, que es lo que decía antes de que el objetivo existiera.
   */
  private targetNote(metrics: ClaimMetrics, decided: number): string {
    const target = metrics.resolutionTarget;
    if (!target.enabled || target.targetDays === null || decided === 0) {
      return 'de la denuncia a la decisión';
    }
    if (target.exceeded === 0) {
      return `Objetivo: ${target.targetDays} d · los ${decided} lo cumplieron`;
    }
    return `Objetivo: ${target.targetDays} d · ${target.exceeded} de ${decided} lo superaron`;
  }

  /**
   * El desglose bajo el tiempo promedio: cuánto de la demora fue esperando a un tercero.
   *
   * Se muestra sólo cuando la espera llega a una hora. Por debajo de eso no es espera: es un
   * expediente que pasó unos segundos por "falta documentación" mientras alguien lo movía, y el
   * renglón terminaría diciendo "0 h esperando a terceros", que ocupa lugar para no decir nada.
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
      // El ramo no comunica estado: sin semáforo.
      tone: 'neutral' as StatusTone,
    })),
  );

  /**
   * Altas contra resoluciones, en barras. Lo que entra va en tinta (es volumen) y lo que se resuelve
   * en el teal de "resuelto", que es el mismo con el que la app pinta un expediente aprobado. Las
   * dos series en gris se leían apagadas y costaba separarlas de un vistazo.
   */
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
      (point) => `${this.bucketLabel(point)}: ${point.reported} denunciados, ${point.resolved} resueltos`,
    );
    return `Altas y resoluciones a lo largo del período. ${parts.join('. ')}.`;
  });

  /** Por qué la línea de tiempo está agrupada así. Con poco volumen, el día a día es casi todo ceros. */
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

  // ─── Formato ───────────────────────────────────────────────────────────────────────

  private percent(rate: number | null | undefined): string {
    return rate === null || rate === undefined ? '—' : formatPercent(rate, this.locale, '1.0-0');
  }

  /**
   * La variación contra el período anterior, con su flecha.
   *
   * `good` dice qué dirección es la buena, porque del signo no se deduce: que suba el tiempo de
   * resolución es malo y que suba la tasa de Fast Track es bueno. Sin eso, un ▲ rojo al lado de un
   * número que mejoró sería exactamente el tipo de cartel que hace desconfiar del tablero entero.
   */
  private trendOf(
    current: number | null,
    previous: number | null,
    unit: 'count' | 'rate' | 'hours',
    good: 'up' | 'down' | 'neither',
    base = Number.POSITIVE_INFINITY,
  ): string {
    if (base < MIN_COMPARISON_BASE) {
      return '';
    }
    const change = delta(current, previous);
    if (change.value === null || change.direction === 'flat') {
      return '';
    }
    const arrow = change.direction === 'up' ? '▲' : '▼';
    const size = Math.abs(change.value);
    const amount =
      unit === 'rate'
        ? formatPercent(size, this.locale, '1.0-0')
        : unit === 'hours'
          ? resolutionTimeLabel(size)
          : formatNumber(size, this.locale, '1.0-0');
    // Que entren más o menos siniestros no es mejor ni peor: es el volumen del mes. Poner un
    // veredicto ahí sería inventar una opinión que el dato no tiene.
    const tail =
      good === 'neither'
        ? 'vs. el período anterior'
        : `${change.direction === good ? 'mejor' : 'peor'} que el período anterior`;
    return `${arrow} ${amount} ${tail}`;
  }

  /** El punto de la línea de tiempo, como lo escribiría alguien: "14/06" o "jun 2026". */
  private bucketLabel(point: TimelinePoint): string {
    const date = new Date(`${point.bucket}T00:00:00`);
    if (this.data()?.granularity === 'MONTH') {
      return date.toLocaleDateString('es-AR', { month: 'short', year: 'numeric' });
    }
    return date.toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit' });
  }
}

/** Qué parte de lo decidido quedó dentro del objetivo. Null sin objetivo: no hay barra que pintar. */
function targetShare(metrics: ClaimMetrics, decided: number): number | null {
  const target = metrics.resolutionTarget;
  if (!target.enabled || target.targetDays === null || decided === 0) {
    return null;
  }
  return (decided - target.exceeded) / decided;
}

/**
 * Verde si la mayoría quedó dentro del objetivo, ámbar si se pasó más de un cuarto, rojo si se pasó
 * más de la mitad. Los cortes son de lectura, no de negocio: el número exacto está al lado.
 */
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

/** Fecha ISO de hace N días, que es lo que espera un input de tipo date. */
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
