import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable, catchError, combineLatest, map, of, startWith } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { PrefetchService } from '../prefetch.service';
import { AnalystWorkload, CaseService, HomeSummary, LensSummary } from '../../cases/case.service';
import {
  PendingSettlement,
  SettlementAuthoritiesService,
} from '../../admin/settlement-authorities.service';
import { CaseResponse } from '../../../core/models/case';
import { caseStatusTone, workStatusLabel } from '../../../core/models/case-status';
import { riskFactorLabel } from '../../../core/models/business-rules';
import { RiskBand, riskBandLabel, riskBandTone } from '../../../core/models/risk-band';
import { StatusTone } from '../../../core/models/status-tone';
import { greetingForTimeOfDay, longDate } from '../../../core/util/datetime';
import { formatMoney } from '../../../core/util/money';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { LoadingComponent } from '../../../shared/ui/loading/loading.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { AppReadyService } from '../../../core/app-ready.service';
import { fitRows } from '../fit-rows';
import { SetupCardComponent } from './setup-card/setup-card.component';
import { growBar, listStagger, staggerReveal } from '../../../shared/animations';

const SETTLEMENTS_SHOWN = 3;
/** Shown where the columns stack (phone); on a desktop, as many as fit. */
const ALERTS_SHOWN = 5;
/** Fetched, so a tall screen still has alerts to fill the card with. */
const ALERTS_FETCH = 20;

interface HomeData {
  lens: LensSummary | null;
  summary: HomeSummary | null;
  settlements: PendingSettlement[] | null;
  alerts: CaseResponse[] | null;
  workload: AnalystWorkload[] | null;
}

type HomeState = { status: 'loading' } | { status: 'ok'; data: HomeData };

interface Shortcut {
  value: number | null;
  label: string;
  flag: string | null;
  link: string;
  query: Record<string, string> | null;
}

interface DeadlineSegment {
  label: string;
  value: number;
  tone: StatusTone;
}

@Component({
  selector: 'app-referent-home',
  imports: [
    RouterLink,
    CardComponent,
    BadgeComponent,
    ButtonComponent,
    EmptyStateComponent,
    LoadingComponent,
    InlineLoadingComponent,
    SetupCardComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal, listStagger, growBar],
  templateUrl: './referent-home.component.html',
  styleUrl: './referent-home.component.scss',
})
export class ReferentHomeComponent {
  private readonly cases = inject(CaseService);
  private readonly settlementsService = inject(SettlementAuthoritiesService);
  private readonly session = inject(AuthSessionService);
  private readonly appReady = inject(AppReadyService);

  protected readonly greeting = greetingForTimeOfDay();
  protected readonly today = longDate();
  protected readonly firstName = computed(() => this.session.session()?.nombre ?? '');

  // No `assignedToMe`: the referente sees the whole insurer's operation. Each piece degrades on
  // its own, so one failed count shows a dash instead of an empty home. combineLatest, not
  // forkJoin: cached pieces show at once instead of waiting for every refresh.
  private readonly state = toSignal(
    combineLatest({
      // The same counts as the inbox tabs, so both screens always agree.
      lens: this.orNull(this.cases.lensSummary()),
      summary: this.orNull(this.cases.homeSummary()),
      settlements: this.orNull(this.settlementsService.pending()),
      alerts: this.orNull(
        this.cases
          .cachedList({
            fraudAlert: true,
            scope: 'OPEN',
            sort: 'riskScore,desc',
            size: ALERTS_FETCH,
          })
          .pipe(map((page) => page.content)),
      ),
      workload: this.orNull(this.cases.analystWorkload()),
    }).pipe(
      map((data): HomeState => ({ status: 'ok', data })),
      startWith<HomeState>({ status: 'loading' }),
    ),
    { initialValue: { status: 'loading' } as HomeState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');

  /** Once the home has loaded, warms the screens this role opens most (`PrefetchService`). */
  private readonly warmUp = inject(PrefetchService).afterHomeLoads(() => !this.loading());
  private readonly data = computed<HomeData | null>(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : null;
  });

  // The full-viewport loader shows only on app startup; later visits use an inline spinner.
  protected readonly showFullLoader = computed(() => this.loading() && !this.appReady.ready());
  private readonly markReady = effect(() => {
    if (!this.loading()) {
      this.appReady.markReady();
    }
  });

  private readonly summary = computed(() => this.data()?.summary ?? null);
  protected readonly settlements = computed(() => this.data()?.settlements ?? null);
  protected readonly shownSettlements = computed(() =>
    (this.settlements() ?? []).slice(0, SETTLEMENTS_SHOWN),
  );
  // As many alerts as fit in the height left, the remainder spread among them: one screen, no
  // gap under the last row and no scroll. Measured, since it depends on the screen.
  private readonly mainCol = viewChild<ElementRef<HTMLElement>>('mainCol');
  private readonly fitAlerts = signal(ALERTS_SHOWN);
  protected readonly rowExtra = signal(0);
  private readonly fitList = effect((onCleanup) => {
    const col = this.mainCol()?.nativeElement;
    if (!col) return;
    const observer = new ResizeObserver(() => this.fit(col));
    // The cards too: one settling its height after the first measure doesn't resize the column.
    observer.observe(col);
    Array.from(col.children).forEach((card) => observer.observe(card));
    onCleanup(() => observer.disconnect());
  });

  protected readonly alerts = computed(() => {
    const alerts = this.data()?.alerts;
    return alerts ? alerts.slice(0, this.fitAlerts()) : null;
  });
  protected readonly fraudCount = computed(() => this.data()?.lens?.open.fraud ?? null);

  protected readonly workload = computed(() => this.data()?.workload ?? null);
  // Floor of 1 avoids dividing by zero when nobody has cases.
  private readonly maxLoad = computed(() =>
    Math.max(1, ...(this.workload() ?? []).map((a) => a.activeCases)),
  );

  protected readonly shortcuts = computed<Shortcut[]>(() => {
    const lens = this.data()?.lens;
    const s = this.summary();
    const pending = this.settlements();
    const pendingTotal = (pending ?? []).reduce((sum, p) => sum + p.settledAmount, 0);
    const analystsOverdue = (this.workload() ?? []).filter((a) => a.overdueCases > 0).length;
    const oldest = this.oldestUnassignedDays();
    const items: Shortcut[] = [
      {
        value: lens?.open.total ?? null,
        label: 'Abiertos',
        flag: s ? `${s.deadlines.paused} en pausa` : null,
        link: '/inbox',
        query: null,
      },
      {
        value: lens?.open.fraud ?? null,
        label: 'Riesgo de fraude',
        flag: null,
        link: '/inbox',
        query: { fraudAlert: 'true' },
      },
      {
        value: lens?.open.unassigned ?? null,
        label:
          oldest == null
            ? 'Sin asignar'
            : `Sin asignar · hasta ${oldest} ${oldest === 1 ? 'día' : 'días'}`,
        flag: null,
        link: '/inbox',
        query: { unassigned: 'true' },
      },
      {
        value: pending?.length ?? null,
        label: pending?.length ? `Para autorizar · ${formatMoney(pendingTotal)}` : 'Para autorizar',
        flag: null,
        link: '/insurer/settlements',
        query: null,
      },
      {
        value: s?.deadlines.overdue ?? null,
        label: 'Plazo vencido',
        flag:
          analystsOverdue > 0
            ? `${analystsOverdue} ${analystsOverdue === 1 ? 'analista' : 'analistas'}`
            : null,
        link: '/inbox',
        query: { deadline: 'OVERDUE' },
      },
      {
        value: s?.stale ?? null,
        label: `Sin movimiento +${s?.staleDays ?? 15} días`,
        flag: null,
        link: '/inbox',
        query: { staleDays: String(s?.staleDays ?? 15) },
      },
    ];
    return items;
  });

  protected readonly deadlines = computed(() => {
    const d = this.summary()?.deadlines;
    if (!d) return null;
    const segments: DeadlineSegment[] = [
      { label: 'En término', value: d.onTime, tone: 'ok' },
      { label: 'Por vencer', value: d.dueSoon, tone: 'warning' },
      { label: 'Vencidos', value: d.overdue, tone: 'danger' },
      { label: 'En pausa', value: d.paused, tone: 'neutral' },
    ];
    const total = segments.reduce((sum, seg) => sum + seg.value, 0);
    return { segments, total };
  });

  protected oldestUnassignedDays(): number | null {
    const at = this.summary()?.oldestUnassignedAt;
    if (!at) return null;
    return Math.max(0, Math.floor((Date.now() - new Date(at).getTime()) / (1000 * 60 * 60 * 24)));
  }

  protected barPct(activeCases: number): number {
    return Math.round((activeCases / this.maxLoad()) * 100);
  }

  protected money(amount: number | null): string {
    return formatMoney(amount);
  }

  /** The strongest factor behind the score, which is what the referente wants to read first. */
  protected alertReason(c: CaseResponse): string {
    const top = [...(c.riskBreakdown ?? [])].sort(
      (a, b) => b.weightedContribution - a.weightedContribution,
    )[0];
    if (top) return riskFactorLabel(top.factorId);
    return c.analysisReasons?.[0] ?? '';
  }

  protected riskLabel(c: CaseResponse): string {
    return c.riskBand ? riskBandLabel(c.riskBand as RiskBand) : '—';
  }

  protected riskTone(c: CaseResponse): StatusTone {
    return riskBandTone(c.riskBand as RiskBand | null);
  }

  protected alertStatus(c: CaseResponse): { label: string; tone: StatusTone } {
    if (c.deadlinePriority === 'OVERDUE') return { label: 'Plazo vencido', tone: 'danger' };
    if (c.assignedAnalystId == null) return { label: 'Sin asignar', tone: 'neutral' };
    return {
      label: workStatusLabel(c.status, c.settlementStatus),
      tone: caseStatusTone(c.status),
    };
  }

  protected displayInsured(name: string | null): string {
    return name ?? 'Sin identificar';
  }

  private fit(col: HTMLElement): void {
    const card = col.querySelector<HTMLElement>('.alerts-card');
    const list = col.querySelector<HTMLElement>('.alerts');
    if (!card || !list) return;
    const alerts = this.data()?.alerts?.length ?? 0;
    const fit = fitRows(col, card, list, alerts, this.rowExtra(), ALERTS_SHOWN);
    if (fit) {
      this.fitAlerts.set(fit.rows);
      this.rowExtra.set(fit.extra);
    }
  }

  private orNull<T>(source: Observable<T>): Observable<T | null> {
    return source.pipe(catchError(() => of(null)));
  }
}
