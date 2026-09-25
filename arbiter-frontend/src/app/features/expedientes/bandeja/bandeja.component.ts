import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  HostListener,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap, Router, RouterLink } from '@angular/router';
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  map,
  Observable,
  of,
  startWith,
  switchMap,
} from 'rxjs';
import * as XLSX from 'xlsx';

import {
  ExpedienteService,
  ExpedienteListParams,
  LensCounts,
  LensSummary,
} from '../expediente.service';
import { CaseNavigationService } from '../case-navigation.service';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { UserAdminService } from '../../../core/auth/user-admin.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { clasificacionLabel, clasificacionTone } from '../../../core/models/clasificacion';
import { formatDate as formatDateUtil } from '../../../core/util/datetime';
import {
  DeadlinePriority,
  deadlinePriorityLabel,
  deadlinePriorityTone,
  isDeadlinePrioritized,
} from '../../../core/models/deadline-priority';
import {
  CaseStatus,
  estadoLabel,
  estadoTone,
  riskBandEmptyLabel,
} from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { PaginationComponent } from '../../../shared/ui/pagination/pagination.component';
import { FraudGaugeComponent } from '../../../shared/ui/fraud-gauge/fraud-gauge.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import {
  MenuButtonComponent,
  MenuItem,
} from '../../../shared/ui/menu-button/menu-button.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { fadeStagger, staggerReveal } from '../../../shared/animations';

// JPA property paths of Case (Spring Data sorts by property, not by SQL column).
type SortField =
  | 'id'
  | 'status'
  | 'insuredName'
  | 'claimCause'
  | 'eventDate'
  | 'claimedAmount'
  | 'responseDeadline'
  | 'riskBand'
  | 'analysisClassification'
  | 'analyst.surname';
type SortDir = 'asc' | 'desc';

interface ColumnDef {
  field: SortField;
  label: string;
}

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse[]; totalElements: number; totalPages: number }
  | { status: 'error' };

type Lifecycle = 'open' | 'closed' | 'all';

type Ownership = 'mine' | 'assigned' | 'unassigned' | 'fraud';

const SCOPE_OF: Record<Lifecycle, NonNullable<ExpedienteListParams['scope']>> = {
  open: 'OPEN',
  closed: 'CLOSED',
  all: 'ALL',
};

const NO_COUNTS: LensCounts = { total: 0, mine: 0, assigned: 0, unassigned: 0, fraud: 0 };

const NO_SUMMARY: LensSummary = { open: NO_COUNTS, closed: NO_COUNTS, all: NO_COUNTS };

const LINK_PARAMS = ['scope', 'unassigned', 'fraudAlert', 'status', 'staleDays'];

@Component({
  selector: 'app-bandeja',
  imports: [
    RouterLink,
    CardComponent,
    BadgeComponent,
    ButtonComponent,
    InputComponent,
    SelectComponent,
    PaginationComponent,
    FraudGaugeComponent,
    EmptyStateComponent,
    MenuButtonComponent,
    InlineLoadingComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [fadeStagger, staggerReveal],
  templateUrl: './bandeja.component.html',
  styleUrl: './bandeja.component.scss',
})
export class BandejaComponent {
  private readonly service = inject(ExpedienteService);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly caseNav = inject(CaseNavigationService);
  private readonly session = inject(AuthSessionService);
  private readonly users = inject(UserAdminService);
  private readonly route = inject(ActivatedRoute);

  constructor() {
    // Read live, not only on init: the inbox may already be on screen when the top bar searches.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const q = params.get('q') ?? '';
      if (q !== this.qDraft()) {
        this.qDraft.set(q);
        this.page.set(0);
      }
      this.applyLinkedLens(params);
    });

    this.service.claimCauseNames().subscribe({
      next: (names) => this.claimCauseOptions.set(names.map((n) => ({ value: n, label: n }))),
      error: () => {
        /* best-effort: the filter just stays empty */
      },
    });

    // Supervisor-only endpoint.
    if (this.isReferente()) {
      this.service.analystWorkload().subscribe({
        next: (team) =>
          this.analystOptions.set(team.map((a) => ({ value: String(a.analystId), label: a.name }))),
        error: () => {
          /* best-effort: the filter just stays empty */
        },
      });
    }
  }

  // ───────────────── Lens tabs ─────────────────
  // One bar, two axes combined with AND: a lifecycle is always picked, an ownership is optional.

  protected readonly lifecycle = signal<Lifecycle>('open');
  protected readonly ownership = signal<Ownership | null>(null);

  protected readonly lifecycleTabs: { value: Lifecycle; label: string }[] = [
    { value: 'open', label: 'En curso' },
    { value: 'closed', label: 'Cerrados' },
    { value: 'all', label: 'Todos' },
  ];

  protected readonly ownershipTabs = computed<{ value: Ownership; label: string }[]>(() => [
    this.isReferente()
      ? { value: 'assigned', label: 'Asignados' }
      : { value: 'mine', label: 'Mis asignados' },
    { value: 'unassigned', label: 'Sin asignar' },
    { value: 'fraud', label: 'Riesgo de fraude' },
  ]);

  protected setLifecycle(lifecycle: Lifecycle): void {
    this.lifecycle.set(lifecycle);
    this.page.set(0);
  }

  protected toggleOwnership(ownership: Ownership): void {
    this.ownership.update((current) => (current === ownership ? null : ownership));
    this.page.set(0);
  }

  private applyLinkedLens(params: ParamMap): void {
    if (!LINK_PARAMS.some((key) => params.has(key))) {
      return;
    }
    const scope = params.get('scope');
    this.lifecycle.set(scope === 'CLOSED' ? 'closed' : scope === 'ALL' ? 'all' : 'open');
    this.ownership.set(
      params.get('unassigned') === 'true'
        ? 'unassigned'
        : params.get('fraudAlert') === 'true'
          ? 'fraud'
          : null,
    );
    this.statusFilter.set(params.get('status') ?? '');
    const staleDays = Number(params.get('staleDays'));
    this.staleDaysFilter.set(staleDays > 0 ? staleDays : null);
    this.page.set(0);
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: Object.fromEntries(LINK_PARAMS.map((key) => [key, null])),
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  // ───────────────── Filters, search, sort and paging ─────────────────
  protected readonly statusFilter = signal('');
  protected readonly claimCauseFilter = signal('');
  protected readonly riskBandFilter = signal('');
  protected readonly analystFilter = signal('');
  protected readonly eventDateFrom = signal('');
  protected readonly eventDateTo = signal('');
  protected readonly followUpFilter = signal('');
  protected readonly staleDaysFilter = signal<number | null>(null);
  protected readonly qDraft = signal('');
  protected readonly sortField = signal<SortField>('id');
  protected readonly sortDir = signal<SortDir>('desc');
  protected readonly page = signal(0);
  protected readonly size = signal(10);

  // Only free text is debounced; selects and dates apply immediately.
  private readonly qDebounced = toSignal(
    toObservable(this.qDraft).pipe(debounceTime(350), distinctUntilChanged()),
    { initialValue: '' },
  );

  // Filters without the active tab: the lens counts use this so each tab shows what it will contain.
  private readonly activeFilters = computed<ExpedienteListParams>(() => ({
    status: this.statusFilter() || undefined,
    claimCause: this.claimCauseFilter() || undefined,
    riskBand: (this.riskBandFilter() || undefined) as ExpedienteListParams['riskBand'],
    analystId: this.analystFilter() ? Number(this.analystFilter()) : undefined,
    eventDateFrom: this.eventDateFrom() || undefined,
    eventDateTo: this.eventDateTo() || undefined,
    q: this.qDebounced() || undefined,
    followUp: (this.followUpFilter() || undefined) as ExpedienteListParams['followUp'],
    staleDays: this.staleDaysFilter() ?? undefined,
  }));

  /** Picking a status leaves the lifecycle tabs; otherwise e.g. "Aprobado" under "En curso" is always empty. */
  private readonly statusFilterWidensScope = effect(() => {
    const status = this.statusFilter();
    untracked(() => {
      if (status && this.lifecycle() !== 'all') {
        this.lifecycle.set('all');
      }
    });
  });

  /** Bumped after assign/unassign to refetch. */
  private readonly reloadTrigger = signal(0);

  /** Filters + lens: single source for both the table and the export, so the file matches the screen. */
  private readonly viewFilters = computed<ExpedienteListParams>(() => ({
    ...this.activeFilters(),
    sort: `${this.sortField()},${this.sortDir()}`,
    scope: SCOPE_OF[this.lifecycle()],
    assignedToMe: this.ownership() === 'mine',
    assigned: this.ownership() === 'assigned',
    unassigned: this.ownership() === 'unassigned',
    fraudAlert: this.ownership() === 'fraud',
  }));

  private readonly requestParams = computed<ExpedienteListParams & { reload: number }>(() => ({
    ...this.viewFilters(),
    page: this.page(),
    size: this.size(),
    reload: this.reloadTrigger(),
  }));

  private readonly state = toSignal(
    toObservable(this.requestParams).pipe(
      switchMap((params) =>
        this.service.list(params).pipe(
          map((page): LoadState => ({
            status: 'ok',
            data: page.content,
            totalElements: page.totalElements,
            totalPages: page.totalPages,
          })),
          startWith<LoadState>({ status: 'loading' }),
          catchError(() => of<LoadState>({ status: 'error' })),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as LoadState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly hasError = computed(() => this.state().status === 'error');

  // Tells the first load (spinner only) apart from refetches (filters stay, only the table is replaced).
  protected readonly hasLoaded = signal(false);
  private readonly latchLoaded = effect(() => {
    if (!this.loading()) {
      this.hasLoaded.set(true);
    }
  });
  protected readonly firstLoad = computed(() => this.loading() && !this.hasLoaded());

  protected readonly cases = computed<ExpedienteResponse[]>(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : [];
  });

  // Lets the detail navigate previous/next in this visible order.
  private readonly publishSequence = effect(() => {
    this.caseNav.setSequence(this.cases().map((c) => c.id));
  });

  protected readonly totalElements = computed(() => {
    const s = this.state();
    return s.status === 'ok' ? s.totalElements : 0;
  });

  protected readonly totalPages = computed(() => {
    const s = this.state();
    return s.status === 'ok' ? s.totalPages : 0;
  });

  protected readonly isEmpty = computed(
    () => this.state().status === 'ok' && this.cases().length === 0,
  );

  /** Lens counts honor the current filters: how many of what you're looking at, not absolute totals. */
  private readonly counts = toSignal(
    toObservable(
      computed(() => ({
        filters: this.activeFilters(),
        reload: this.reloadTrigger(),
      })),
    ).pipe(
      switchMap(({ filters }) =>
        this.service.lensSummary(filters).pipe(catchError(() => of(NO_SUMMARY))),
      ),
    ),
    { initialValue: NO_SUMMARY },
  );

  protected lifecycleCount(lifecycle: Lifecycle): number {
    const row = this.counts()[lifecycle];
    const ownership = this.ownership();
    return ownership ? row[ownership] : row.total;
  }

  protected ownershipCount(ownership: Ownership): number {
    return this.counts()[this.lifecycle()][ownership];
  }

  protected readonly hasActiveFilters = computed(
    () =>
      !!(
        this.statusFilter() ||
        this.claimCauseFilter() ||
        this.riskBandFilter() ||
        this.eventDateFrom() ||
        this.eventDateTo() ||
        this.followUpFilter() ||
        this.analystFilter() ||
        this.staleDaysFilter() ||
        this.qDebounced()
      ),
  );

  protected readonly emptyByLens = computed(
    () =>
      this.isEmpty() &&
      !this.hasActiveFilters() &&
      (this.lifecycle() !== 'all' || this.ownership() !== null),
  );

  protected readonly emptyLensMessage = computed<{ message: string; sub: string }>(() => {
    const ownership = this.ownershipTabs().find((tab) => tab.value === this.ownership());
    if (ownership) {
      const lifecycle = this.lifecycleTabs.find((tab) => tab.value === this.lifecycle())!;
      return {
        message: `No hay expedientes en «${lifecycle.label}» con «${ownership.label}».`,
        sub: 'Sin coincidencias',
      };
    }
    return this.lifecycle() === 'open'
      ? {
          message: 'No hay expedientes en curso. Probá con «Todos» para ver los ya cerrados.',
          sub: 'Nada pendiente',
        }
      : { message: 'Todavía no hay expedientes cerrados.', sub: 'Sin cerrados' };
  });

  // ───────────────── Select catalogs ─────────────────
  // Every CaseStatus value, in lifecycle order: keep in sync with the enum.
  private static readonly STATUS_VALUES: CaseStatus[] = [
    'PENDING_CLASSIFICATION',
    'PENDING_ANALYST_REVIEW',
    'CLASSIFICATION_FAILED',
    'AWAITING_DOCUMENTATION',
    'PENDING_EXPERT_REPORT',
    'PENDING_REPAIR',
    'APPROVED',
    'REJECTED',
    'LAPSED',
  ];

  protected readonly statusOptions: SelectOption[] = BandejaComponent.STATUS_VALUES.map((s) => ({
    value: s,
    label: estadoLabel(s),
  }));

  protected readonly claimCauseOptions = signal<SelectOption[]>([]);

  /** Empty for analysts, who don't get this filter. */
  protected readonly analystOptions = signal<SelectOption[]>([]);

  // Same labels as app-fraud-gauge.
  protected readonly riskBandOptions: SelectOption[] = [
    { value: 'LOW', label: 'Bajo' },
    { value: 'MEDIUM', label: 'Medio' },
    { value: 'HIGH', label: 'Alto' },
    { value: 'CRITICAL', label: 'Crítico' },
  ];

  protected readonly followUpOptions: SelectOption[] = [
    { value: 'EXPERT_REPORT_RECEIVED', label: 'Volvió del perito' },
    { value: 'REPAIR_REPORT_RECEIVED', label: 'Volvió del servicio técnico' },
    { value: 'RETURNED_BY_REFERENT', label: 'Devuelto por el referente' },
    { value: 'AWAITING_REFERENT', label: 'Esperando al referente' },
  ];

  protected readonly columns: ColumnDef[] = [
    { field: 'id', label: 'N°' },
    { field: 'status', label: 'Estado' },
    { field: 'responseDeadline', label: 'Plazo' },
    { field: 'insuredName', label: 'Asegurado' },
    { field: 'claimCause', label: 'Tipo de siniestro' },
    { field: 'eventDate', label: 'Fecha del hecho' },
    { field: 'claimedAmount', label: 'Importe reclamado' },
    { field: 'riskBand', label: 'Riesgo' },
    { field: 'analysisClassification', label: 'Clasificación' },
    { field: 'analyst.surname', label: 'Analista' },
  ];

  protected onSearchInput(v: string): void {
    this.qDraft.set(v);
    this.page.set(0);
    // Mirrored in ?q=, otherwise repeating the same top-bar search wouldn't change the URL and be ignored.
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { q: v || null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  // ───────────────── Filters popover ─────────────────
  // Draft values are applied on "Aplicar filtros" and discarded if closed without applying.
  protected readonly filtersOpen = signal(false);
  protected readonly draftStatus = signal('');
  protected readonly draftClaimCause = signal('');
  protected readonly draftRiskBand = signal('');
  protected readonly draftAnalyst = signal('');
  protected readonly draftDateFrom = signal('');
  protected readonly draftDateTo = signal('');
  protected readonly draftFollowUp = signal('');

  protected openFilters(): void {
    this.draftStatus.set(this.statusFilter());
    this.draftClaimCause.set(this.claimCauseFilter());
    this.draftRiskBand.set(this.riskBandFilter());
    this.draftAnalyst.set(this.analystFilter());
    this.draftDateFrom.set(this.eventDateFrom());
    this.draftDateTo.set(this.eventDateTo());
    this.draftFollowUp.set(this.followUpFilter());
    this.filtersOpen.set(true);
  }
  protected closeFilters(): void {
    this.filtersOpen.set(false);
  }
  protected toggleFilters(): void {
    if (this.filtersOpen()) {
      this.closeFilters();
    } else {
      this.openFilters();
    }
  }

  // Closes on outside click or Escape, like the kit's menu-button/select.
  @HostListener('document:click', ['$event'])
  protected onDocumentClick(event: MouseEvent): void {
    if (!this.filtersOpen()) return;
    const target = event.target as HTMLElement | null;
    if (target && !target.closest('.filters-anchor')) {
      this.closeFilters();
    }
  }
  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.filtersOpen()) {
      this.closeFilters();
    }
  }
  protected applyFilters(): void {
    this.statusFilter.set(this.draftStatus());
    this.claimCauseFilter.set(this.draftClaimCause());
    this.riskBandFilter.set(this.draftRiskBand());
    this.analystFilter.set(this.draftAnalyst());
    this.eventDateFrom.set(this.draftDateFrom());
    this.eventDateTo.set(this.draftDateTo());
    this.followUpFilter.set(this.draftFollowUp());
    this.page.set(0);
    this.filtersOpen.set(false);
  }
  protected clearDraft(): void {
    this.draftStatus.set('');
    this.draftClaimCause.set('');
    this.draftRiskBand.set('');
    this.draftAnalyst.set('');
    this.draftDateFrom.set('');
    this.draftDateTo.set('');
    this.draftFollowUp.set('');
  }

  protected readonly activeFilterCount = computed(() => {
    let n = 0;
    if (this.statusFilter()) n++;
    if (this.claimCauseFilter()) n++;
    if (this.riskBandFilter()) n++;
    if (this.analystFilter()) n++;
    if (this.eventDateFrom()) n++;
    if (this.eventDateTo()) n++;
    if (this.followUpFilter()) n++;
    return n;
  });

  protected readonly activeChips = computed<{ key: string; label: string }[]>(() => {
    const chips: { key: string; label: string }[] = [];
    if (this.statusFilter())
      chips.push({ key: 'status', label: `Estado: ${estadoLabel(this.statusFilter())}` });
    if (this.claimCauseFilter())
      chips.push({ key: 'claimCause', label: `Tipo: ${this.claimCauseFilter()}` });
    if (this.riskBandFilter())
      chips.push({
        key: 'riskBand',
        label: `Fraude: ${this.riskBandLabel(this.riskBandFilter())}`,
      });
    if (this.analystFilter())
      chips.push({ key: 'analyst', label: `Analista: ${this.analystName(this.analystFilter())}` });
    if (this.eventDateFrom())
      chips.push({ key: 'dateFrom', label: `Desde: ${this.formatDate(this.eventDateFrom())}` });
    if (this.eventDateTo())
      chips.push({ key: 'dateTo', label: `Hasta: ${this.formatDate(this.eventDateTo())}` });
    if (this.followUpFilter())
      chips.push({ key: 'followUp', label: this.followUpLabel(this.followUpFilter()) });
    const staleDays = this.staleDaysFilter();
    if (staleDays)
      chips.push({ key: 'staleDays', label: `Sin movimiento hace más de ${staleDays} días` });
    return chips;
  });

  protected removeChip(key: string): void {
    switch (key) {
      case 'status':
        this.statusFilter.set('');
        break;
      case 'claimCause':
        this.claimCauseFilter.set('');
        break;
      case 'riskBand':
        this.riskBandFilter.set('');
        break;
      case 'analyst':
        this.analystFilter.set('');
        break;
      case 'dateFrom':
        this.eventDateFrom.set('');
        break;
      case 'dateTo':
        this.eventDateTo.set('');
        break;
      case 'followUp':
        this.followUpFilter.set('');
        break;
      case 'staleDays':
        this.staleDaysFilter.set(null);
        break;
    }
    this.page.set(0);
  }

  private followUpLabel(value: string): string {
    return this.followUpOptions.find((o) => o.value === value)?.label ?? value;
  }

  private analystName(id: string): string {
    return this.analystOptions().find((o) => o.value === id)?.label ?? id;
  }

  /** Leaves the free-text search untouched. */
  protected clearAllChips(): void {
    this.statusFilter.set('');
    this.claimCauseFilter.set('');
    this.riskBandFilter.set('');
    this.analystFilter.set('');
    this.eventDateFrom.set('');
    this.eventDateTo.set('');
    this.followUpFilter.set('');
    this.staleDaysFilter.set(null);
    this.page.set(0);
  }

  protected toggleSort(field: SortField): void {
    if (this.sortField() === field) {
      this.sortDir.update((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      this.sortField.set(field);
      this.sortDir.set('asc');
    }
    this.page.set(0);
  }

  protected onPageChange(page: number): void {
    this.page.set(page);
  }

  protected onSizeChange(size: number): void {
    this.size.set(size);
    this.page.set(0);
  }

  protected goTo(id: number): void {
    this.router.navigate(['/cases', id]);
  }

  // ───────────────── Assignment ─────────────────

  protected readonly assigning = signal<number | null>(null);
  protected readonly assignError = signal<string | null>(null);

  private readonly analysts = toSignal(this.users.listAnalysts().pipe(catchError(() => of([]))), {
    initialValue: [],
  });

  protected readonly analystMenuItems = computed<MenuItem[]>(() =>
    this.analysts().map((a) => ({ value: String(a.id), label: `${a.nombre} ${a.apellido}` })),
  );

  /**
   * The per-tenant analyst id, found by email in the (tenant-scoped) analyst list; the session only
   * has the user id. Null for the supervisor.
   */
  private readonly myAnalystId = computed<number | null>(() => {
    const email = this.session.session()?.email;
    return this.analysts().find((a) => a.email === email)?.id ?? null;
  });

  protected readonly canTake = computed(
    () => this.session.session()?.rol === 'ANALISTA_SINIESTROS' && this.myAnalystId() != null,
  );

  /** The supervisor's inbox is read-only for assignment. */
  protected readonly canAssign = computed(
    () => this.session.session()?.rol === 'ANALISTA_SINIESTROS',
  );

  protected readonly isReferente = computed(
    () => this.session.session()?.rol === 'REFERENTE_ASEGURADORA',
  );

  protected isMine(c: ExpedienteResponse): boolean {
    return c.assignedAnalystId != null && c.assignedAnalystId === this.myAnalystId();
  }

  protected analystInitials(c: ExpedienteResponse): string {
    return (c.assignedAnalystName ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]!.toUpperCase())
      .join('');
  }

  /** Sentinel menu value, not an analyst id. */
  private static readonly RELEASE = '__release__';

  protected assignMenuItems(c: ExpedienteResponse): MenuItem[] {
    // The current assignee is left out.
    const others = this.analystMenuItems().filter(
      (item) => item.value !== String(c.assignedAnalystId),
    );
    return c.assignedAnalystId
      ? [...others, { value: BandejaComponent.RELEASE, label: 'Liberar', danger: true }]
      : others;
  }

  protected onAssignMenu(c: ExpedienteResponse, value: string): void {
    if (value === BandejaComponent.RELEASE) {
      this.release(c.id);
    } else {
      this.assignTo(c.id, value);
    }
  }

  protected take(c: ExpedienteResponse): void {
    const me = this.myAnalystId();
    if (me != null) {
      this.runAssignment(c.id, this.service.assign(c.id, me));
    }
  }

  protected assignTo(caseId: number, analystId: string): void {
    this.runAssignment(caseId, this.service.assign(caseId, Number(analystId)));
  }

  protected release(caseId: number): void {
    this.runAssignment(caseId, this.service.unassign(caseId));
  }

  private runAssignment(caseId: number, request: Observable<ExpedienteResponse>): void {
    if (this.assigning() !== null) {
      return;
    }
    this.assigning.set(caseId);
    this.assignError.set(null);
    request.subscribe({
      next: () => {
        this.assigning.set(null);
        // Refetch instead of patching the row: under "Míos" the case must disappear and counts update.
        this.reloadTrigger.update((n) => n + 1);
      },
      error: () => {
        this.assigning.set(null);
        this.assignError.set('No se pudo actualizar la asignación. Intentá de nuevo.');
      },
    });
  }

  // ───────────────── Cell rendering ─────────────────
  protected estadoLabel(status: string): string {
    return estadoLabel(status);
  }

  protected estadoTone(status: string): StatusTone {
    return estadoTone(status);
  }

  protected clasificacionLabel(value: string): string {
    return clasificacionLabel(value);
  }

  protected clasificacionTone(value: string): StatusTone {
    return clasificacionTone(value);
  }

  protected deadlineTone(priority: DeadlinePriority): StatusTone {
    return deadlinePriorityTone(priority);
  }

  protected deadlineLabel(c: ExpedienteResponse): string {
    return deadlinePriorityLabel(c.deadlinePriority, c.responseDeadline);
  }

  protected deadlinePrioritized(c: ExpedienteResponse): boolean {
    return isDeadlinePrioritized(c.deadlinePriority);
  }

  private static readonly RISK_BAND_GAUGE: Record<string, 1 | 2 | 3 | 4> = {
    LOW: 1,
    MEDIUM: 2,
    HIGH: 3,
    CRITICAL: 4,
  };

  protected riskGaugeBand(c: ExpedienteResponse): 1 | 2 | 3 | 4 | null {
    return c.riskBand ? BandejaComponent.RISK_BAND_GAUGE[c.riskBand] : null;
  }

  protected riskGaugeEmptyLabel(c: ExpedienteResponse): string {
    return riskBandEmptyLabel(c.status, c.analysisClassification);
  }

  // `new Date('2026-08-20')` parses as UTC and shifts to the previous day in Argentina.
  protected formatDate(value: string): string {
    return formatDateUtil(value);
  }

  protected formatAmount(value: number | null): string {
    return value != null ? `$${value.toLocaleString('es-AR')}` : '—';
  }

  /** Never falls back to the DNI: shows a placeholder until classification resolves the name. */
  protected displayInsured(c: ExpedienteResponse): string {
    return c.insuredName ?? 'Sin identificar';
  }

  protected displayInsuredId(c: ExpedienteResponse): string {
    return c.insuredId;
  }

  // ───────────────── Export (CSV / XLSX) ─────────────────
  protected readonly exporting = signal(false);

  protected readonly exportOptions: MenuItem[] = [
    { value: 'csv', label: 'CSV (.csv)' },
    { value: 'xlsx', label: 'Excel (.xlsx)' },
  ];

  private static readonly EXPORT_HEADER = [
    'N°',
    'Estado',
    'Asegurado',
    'N° de póliza',
    'Tipo de siniestro',
    'Fecha del hecho',
    'Importe reclamado',
    'Riesgo',
    'Clasificación',
    'Analista',
  ];

  /** Exports every matching case (not just the visible page) by paging through `viewFilters`. */
  protected exportAs(format: string): void {
    if (this.exporting()) {
      return;
    }
    this.exporting.set(true);

    const params: ExpedienteListParams = { ...this.viewFilters(), size: 200 };

    this.fetchAllPages(params, 0, []).subscribe({
      next: (rows) => {
        if (format === 'xlsx') {
          this.downloadXlsx(rows);
        } else {
          this.downloadCsv(rows);
        }
        this.exporting.set(false);
      },
      error: () => {
        this.exporting.set(false);
      },
    });
  }

  private fetchAllPages(
    params: ExpedienteListParams,
    page: number,
    acc: ExpedienteResponse[],
  ): Observable<ExpedienteResponse[]> {
    return this.service.list({ ...params, page }).pipe(
      switchMap((result) => {
        const combined = [...acc, ...result.content];
        return page + 1 >= result.totalPages
          ? of(combined)
          : this.fetchAllPages(params, page + 1, combined);
      }),
    );
  }

  private downloadCsv(rows: ExpedienteResponse[]): void {
    const lines = [
      BandejaComponent.EXPORT_HEADER.join(','),
      ...rows.map((r) =>
        this.toRowCells(r)
          .map((v) => this.csvEscape(v))
          .join(','),
      ),
    ];
    // BOM so Excel reads the file as UTF-8.
    const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
    this.download(blob, 'csv');
  }

  private downloadXlsx(rows: ExpedienteResponse[]): void {
    const aoa = [BandejaComponent.EXPORT_HEADER, ...rows.map((r) => this.toRowCells(r))];
    const sheet = XLSX.utils.aoa_to_sheet(aoa);
    const book = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(book, sheet, 'Expedientes');
    const buffer: ArrayBuffer = XLSX.write(book, { type: 'array', bookType: 'xlsx' });
    const blob = new Blob([buffer], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    this.download(blob, 'xlsx');
  }

  private download(blob: Blob, extension: string): void {
    const url = URL.createObjectURL(blob);
    const link = this.document.createElement('a');
    link.href = url;
    // The lens goes in the filename so the file says whose cases it holds.
    const scope = this.ownership() === 'mine' ? 'mios-' : '';
    link.download = `expedientes-${scope}${this.timestampForFilename()}.${extension}`;
    link.click();
    URL.revokeObjectURL(url);
  }

  private toRowCells(c: ExpedienteResponse): string[] {
    return [
      String(c.id),
      estadoLabel(c.status),
      this.displayInsured(c),
      c.policyNumber,
      c.claimCause,
      this.formatDate(c.eventDate),
      c.claimedAmount != null ? String(c.claimedAmount) : '',
      this.riskBandLabel(c.riskBand),
      c.analysisClassification ? clasificacionLabel(c.analysisClassification) : '',
      c.assignedAnalystName ?? '',
    ];
  }

  private riskBandLabel(band: string | null): string {
    return band ? (this.riskBandOptions.find((o) => o.value === band)?.label ?? band) : '';
  }

  private csvEscape(value: string): string {
    return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
  }

  private timestampForFilename(): string {
    const now = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`;
  }
}
