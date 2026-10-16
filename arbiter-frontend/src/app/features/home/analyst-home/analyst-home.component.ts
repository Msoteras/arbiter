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
import { Observable, catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { PrefetchService } from '../prefetch.service';
import { UserAdminService } from '../../../core/auth/user-admin.service';
import {
  AssignedCaseSummary,
  CaseListParams,
  CaseService,
  HomeSummary,
} from '../../cases/case.service';
import { CaseMessagesService } from '../../cases/case-messages.service';
import { ClaimMetricsService } from '../../admin/dashboard/claim-metrics.service';
import { ClaimMetrics } from '../../admin/dashboard/claim-metrics';
import { CaseResponse } from '../../../core/models/case';
import { CaseMessageInboxItem } from '../../../core/models/case-message';
import {
  DeadlinePriority,
  deadlinePriorityLabel,
  deadlinePriorityTone,
  isDeadlinePrioritized,
} from '../../../core/models/deadline-priority';
import { RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { isPausingStatus } from '../../../core/models/case-status';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDate, greetingForTimeOfDay, longDate } from '../../../core/util/datetime';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { LoadingComponent } from '../../../shared/ui/loading/loading.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { AppReadyService } from '../../../core/app-ready.service';
import { fitRows } from '../fit-rows';
import { listStagger, staggerReveal } from '../../../shared/animations';

/** Shown where the columns stack (phone): there the page scrolls anyway. */
const QUEUE_SIZE = 5;
/** Fetched, so a tall screen still has cases to fill the card with. */
const QUEUE_FETCH = 20;
const RECENT_CHATS = 2;
/** When the messages card gets the spare height. */
const RECENT_CHATS_ROOMY = 5;

interface HomeData {
  summary: HomeSummary | null;
  caseload: AssignedCaseSummary | null;
  queue: CaseResponse[] | null;
  expertAnswered: Set<number>;
  repairAnswered: Set<number>;
  chats: CaseMessageInboxItem[] | null;
}

type HomeState = { status: 'loading' } | { status: 'ok'; data: HomeData };

type WeekState =
  { status: 'loading' } | { status: 'ok'; metrics: ClaimMetrics } | { status: 'none' };

/** A shortcut in the hero strip: a count that opens the inbox already filtered. */
interface Shortcut {
  value: number;
  label: string;
  flag: string | null;
  link: string;
  query: Record<string, string> | null;
}

interface CaseloadRow {
  label: string;
  value: number;
  query: Record<string, string>;
}

@Component({
  selector: 'app-analyst-home',
  imports: [
    RouterLink,
    CardComponent,
    BadgeComponent,
    ButtonComponent,
    EmptyStateComponent,
    LoadingComponent,
    InlineLoadingComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal, listStagger],
  templateUrl: './analyst-home.component.html',
  styleUrl: './analyst-home.component.scss',
})
export class AnalystHomeComponent {
  private readonly cases = inject(CaseService);
  private readonly messages = inject(CaseMessagesService);
  private readonly metrics = inject(ClaimMetricsService);
  private readonly users = inject(UserAdminService);
  private readonly session = inject(AuthSessionService);
  private readonly appReady = inject(AppReadyService);

  protected readonly greeting = greetingForTimeOfDay();
  protected readonly today = longDate();
  protected readonly firstName = computed(() => this.session.session()?.nombre ?? '');

  // Each piece degrades on its own: a failed count shows a dash, not a broken home.
  // combineLatest, not forkJoin: cached pieces show at once instead of waiting for every refresh.
  private readonly state = toSignal(
    combineLatest({
      summary: this.orNull(this.cases.homeSummary()),
      caseload: this.orNull(this.cases.assignedSummary()),
      // More than shown: the order is fixed here, where a paused term can go last.
      queue: this.orNull(
        this.cases
          .cachedList({
            assignedToMe: true,
            scope: 'OPEN',
            sort: 'responseDeadline,asc',
            size: QUEUE_FETCH,
          })
          .pipe(map((page) => byPriority(page.content))),
      ),
      expertAnswered: this.answeredIds({ followUp: 'EXPERT_REPORT_RECEIVED' }),
      repairAnswered: this.answeredIds({ followUp: 'REPAIR_REPORT_RECEIVED' }),
      chats: this.orNull(this.messages.inbox()),
    }).pipe(
      map((data): HomeState => ({ status: 'ok', data })),
      startWith<HomeState>({ status: 'loading' }),
    ),
    { initialValue: { status: 'loading' } as HomeState },
  );

  // Its own stream: the reports module can be asleep, and the rest of the home shouldn't wait.
  private readonly week = toSignal(
    this.users.listAnalysts().pipe(
      map((team) => team.find((a) => a.email === this.session.session()?.email)?.id ?? null),
      switchMap((analystId) =>
        analystId == null
          ? of<WeekState>({ status: 'none' })
          : this.metrics
              .load(
                { range: 'WEEK' },
                { branchId: null, analystId },
                { mode: 'PREVIOUS_PERIOD', from: '', to: '' },
              )
              .pipe(map((metrics): WeekState => ({ status: 'ok', metrics }))),
      ),
      startWith<WeekState>({ status: 'loading' }),
      catchError(() => of<WeekState>({ status: 'none' })),
    ),
    { initialValue: { status: 'loading' } as WeekState },
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

  protected readonly summary = computed(() => this.data()?.summary ?? null);

  protected readonly pending = computed(() => {
    const c = this.data()?.caseload;
    if (!c) return null;
    // Awaiting the referente's sign-off is still "under review", but the analyst already decided.
    return Math.max(0, (c.byStatus['PENDING_ANALYST_REVIEW'] ?? 0) - c.awaitingReferent);
  });

  protected readonly overdue = computed(() => this.summary()?.deadlines.overdue ?? 0);

  protected readonly heroLine = computed<{ text: string; bold: boolean }[]>(() => {
    const parts = [{ text: this.today, bold: false }];
    const pending = this.pending();
    if (pending === null) return parts;
    if (pending > 0) {
      const waiting = pending === 1 ? 'expediente espera' : 'expedientes esperan';
      parts.push({ text: ' · ', bold: false }, { text: `${pending} ${waiting}`, bold: true });
      parts.push({ text: ' tu decisión', bold: false });
    } else {
      parts.push({ text: ' · no tenés expedientes esperando tu decisión', bold: false });
    }
    const overdue = this.overdue();
    if (overdue > 0) {
      const late = overdue === 1 ? 'está vencido' : 'están vencidos';
      parts.push({ text: ' y ', bold: false }, { text: `${overdue} ${late}`, bold: true });
    }
    parts.push({ text: '.', bold: false });
    return parts;
  });

  protected readonly unreadMessages = computed(() =>
    (this.data()?.chats ?? []).reduce((sum, chat) => sum + chat.unreadCount, 0),
  );
  private readonly unreadChats = computed(
    () => (this.data()?.chats ?? []).filter((chat) => chat.unreadCount > 0).length,
  );

  protected readonly shortcuts = computed<Shortcut[]>(() => {
    const s = this.summary();
    const due = s ? s.deadlines.dueSoon + s.deadlines.overdue : 0;
    const unreadChats = this.unreadChats();
    const items: Shortcut[] = [
      {
        value: due,
        label: 'Plazo por vencer',
        flag:
          this.overdue() > 0
            ? `${this.overdue()} ${this.overdue() === 1 ? 'vencido' : 'vencidos'}`
            : null,
        link: '/inbox',
        query: { mine: 'true', deadline: 'DUE_SOON' },
      },
      {
        value: s?.providerReportsReceived ?? 0,
        label: 'Informes recibidos',
        flag: null,
        link: '/inbox',
        query: { mine: 'true', followUp: 'PROVIDER_REPORT_RECEIVED' },
      },
      {
        value: s?.returnedByReferent ?? 0,
        label:
          s?.returnedByReferent === 1 ? 'Devuelto por el referente' : 'Devueltos por el referente',
        flag: null,
        link: '/inbox',
        query: { mine: 'true', followUp: 'RETURNED_BY_REFERENT' },
      },
      {
        value: this.unreadMessages(),
        label: 'Mensajes sin leer',
        flag: unreadChats > 0 ? `en ${unreadChats} ${unreadChats === 1 ? 'chat' : 'chats'}` : null,
        link: '/messages',
        query: null,
      },
      {
        value: s?.stale ?? 0,
        label: `Sin movimiento +${s?.staleDays ?? 15} días`,
        flag: null,
        link: '/inbox',
        query: { mine: 'true', staleDays: String(s?.staleDays ?? 15) },
      },
    ];
    return items;
  });

  // As many cases as fit in the height left, and what's under a whole row spread among them,
  // so the card neither ends in a gap nor makes the page scroll. Measured, since it depends on
  // the screen and on the other cards.
  private readonly mainCol = viewChild<ElementRef<HTMLElement>>('mainCol');
  private readonly fitRows = signal(QUEUE_SIZE);
  /** Pixels added to each row; bound as a CSS variable. */
  protected readonly rowExtra = signal(0);
  private readonly fitQueue = effect((onCleanup) => {
    const col = this.mainCol()?.nativeElement;
    if (!col) return;
    const observer = new ResizeObserver(() => this.fit(col));
    // The cards too: one settling its height after the first measure doesn't resize the column.
    observer.observe(col);
    Array.from(col.children).forEach((card) => observer.observe(card));
    onCleanup(() => observer.disconnect());
  });

  /** With fewer cases than room, the spare height goes to the messages, which can use it. */
  protected readonly queueFills = computed(
    () => (this.data()?.queue?.length ?? 0) >= this.fitRows(),
  );

  protected readonly queue = computed(() => {
    const queue = this.data()?.queue;
    return queue ? queue.slice(0, this.fitRows()) : null;
  });

  protected readonly caseload = computed(() => {
    const c = this.data()?.caseload;
    if (!c) return null;
    const count = (status: string) => c.byStatus[status] ?? 0;
    const mine = (status: string) => ({ mine: 'true', status });
    const yours: CaseloadRow[] = [
      {
        label: 'Pendiente de decisión',
        value: this.pending() ?? 0,
        // Not the status alone: what waits for the referente is counted apart, below.
        query: { mine: 'true', followUp: 'AWAITING_ANALYST' },
      },
      {
        label: 'Clasificación fallida',
        value: count('CLASSIFICATION_FAILED'),
        query: mine('CLASSIFICATION_FAILED'),
      },
    ];
    const waiting: CaseloadRow[] = [
      {
        label: 'Falta documentación',
        value: count('AWAITING_DOCUMENTATION'),
        query: mine('AWAITING_DOCUMENTATION'),
      },
      {
        label: 'Derivado a peritaje',
        value: count('PENDING_EXPERT_REPORT'),
        query: mine('PENDING_EXPERT_REPORT'),
      },
      {
        label: 'Derivado a reparación',
        value: count('PENDING_REPAIR'),
        query: mine('PENDING_REPAIR'),
      },
      {
        label: 'Esperando al referente',
        value: c.awaitingReferent,
        query: { mine: 'true', followUp: 'AWAITING_REFERENT' },
      },
    ];
    const sum = (rows: CaseloadRow[]) => rows.reduce((acc, r) => acc + r.value, 0);
    const yoursTotal = sum(yours);
    const waitingTotal = sum(waiting);
    const total = yoursTotal + waitingTotal;
    return {
      yours,
      waiting,
      yoursTotal,
      waitingTotal,
      total,
      yoursPct: total > 0 ? Math.round((yoursTotal / total) * 100) : 0,
    };
  });

  protected readonly recentChats = computed(() =>
    (this.data()?.chats ?? []).slice(0, this.queueFills() ? RECENT_CHATS : RECENT_CHATS_ROOMY),
  );
  protected readonly chatsFailed = computed(
    () => this.data() != null && this.data()?.chats == null,
  );

  protected readonly weekLoading = computed(() => this.week().status === 'loading');
  protected readonly weekStats = computed(() => {
    const w = this.week();
    if (w.status !== 'ok') return null;
    const { summary, legalDeadline, agreement } = w.metrics;
    const ownHours =
      summary.averageResolutionHours == null
        ? null
        : summary.averageResolutionHours - (summary.averageWaitingHours ?? 0);
    return {
      resolved: summary.resolvedCases,
      approved: summary.approvedCases,
      ownDays:
        ownHours == null
          ? null
          : (ownHours / 24).toLocaleString('es-AR', { maximumFractionDigits: 1 }),
      overdueAtResolution: Math.max(0, legalDeadline.decided - legalDeadline.onTime),
      agreement: agreement.rate == null ? null : Math.round(agreement.rate * 100),
    };
  });

  protected oldestUnassignedDays(): number | null {
    const at = this.summary()?.oldestUnassignedAt;
    if (!at) return null;
    return Math.max(0, Math.floor((Date.now() - new Date(at).getTime()) / (1000 * 60 * 60 * 24)));
  }

  /** Why the case is where it is, in the analyst's words. */
  protected reason(c: CaseResponse): string {
    const data = this.data();
    if (c.status === 'CLASSIFICATION_FAILED') return 'Clasificación fallida: hay que reintentarla';
    if (c.status === 'PENDING_CLASSIFICATION') return 'Clasificándose';
    if (c.status === 'AWAITING_DOCUMENTATION') return 'Esperando documentación del asegurado';
    if (c.status === 'PENDING_EXPERT_REPORT') return 'Esperando el informe del perito';
    if (c.status === 'PENDING_REPAIR') return 'En el servicio técnico';
    if (c.settlementStatus === 'PENDING_AUTHORIZATION') return 'Esperando al referente';
    if (c.settlementStatus === 'RETURNED') return 'El referente devolvió la liquidación';
    if (data?.expertAnswered.has(c.id)) return 'Llegó el informe del perito';
    if (data?.repairAnswered.has(c.id)) return 'El servicio técnico respondió';
    if (c.analysisClassification === 'FAST_TRACK') return 'Fast Track: listo para decidir';
    return `Pendiente de revisión · denunciado el ${formatDate(c.createdAt)}`;
  }

  protected isPaused(c: CaseResponse): boolean {
    return isPausingStatus(c.status);
  }

  protected deadlinePrioritized(c: CaseResponse): boolean {
    return isDeadlinePrioritized(c.deadlinePriority);
  }

  protected deadlineTone(priority: DeadlinePriority): StatusTone {
    return deadlinePriorityTone(priority);
  }

  protected deadlineLabel(c: CaseResponse): string {
    return deadlinePriorityLabel(c.deadlinePriority, c.responseDeadline);
  }

  protected formatDate(value: string): string {
    return formatDate(value);
  }

  protected riskLabel(c: CaseResponse): string {
    if (c.analysisClassification === 'FAST_TRACK') return 'No aplica';
    return c.riskBand ? riskBandLabel(c.riskBand as RiskBand) : 'Sin evaluar';
  }

  protected riskTone(c: CaseResponse): StatusTone {
    const tones: Record<RiskBand, StatusTone> = {
      LOW: 'ok',
      MEDIUM: 'warning',
      HIGH: 'risk',
      CRITICAL: 'danger',
    };
    return c.riskBand ? tones[c.riskBand as RiskBand] : 'neutral';
  }

  protected displayInsured(c: CaseResponse): string {
    return c.insuredName ?? 'Sin identificar';
  }

  protected initials(name: string): string {
    return name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? '')
      .join('');
  }

  private fit(col: HTMLElement): void {
    const card = col.querySelector<HTMLElement>('.queue-card');
    const list = col.querySelector<HTMLElement>('.queue');
    if (!card || !list) return;
    const cases = this.data()?.queue?.length ?? 0;
    const fit = fitRows(col, card, list, cases, this.rowExtra(), QUEUE_SIZE);
    if (fit) {
      this.fitRows.set(fit.rows);
      this.rowExtra.set(fit.extra);
    }
  }

  private orNull<T>(source: Observable<T>): Observable<T | null> {
    return source.pipe(catchError(() => of(null)));
  }

  /** Ids only, to tell in the queue which cases a report came back for. */
  private answeredIds(params: CaseListParams): Observable<Set<number>> {
    return this.cases.cachedList({ ...params, assignedToMe: true, size: 50 }).pipe(
      map((page) => new Set(page.content.map((c) => c.id))),
      catchError(() => of(new Set<number>())),
    );
  }
}

/**
 * What the analyst decides on first, by deadline; then what waits on the referente; last, what
 * waits on a third party, whose frozen deadline would otherwise sort it to the top.
 */
function byPriority(cases: CaseResponse[]): CaseResponse[] {
  const rank = (c: CaseResponse) =>
    isPausingStatus(c.status) ? 2 : c.settlementStatus === 'PENDING_AUTHORIZATION' ? 1 : 0;
  // Array.sort is stable, so the backend's deadline order holds within each group.
  return [...cases].sort((a, b) => rank(a) - rank(b));
}
