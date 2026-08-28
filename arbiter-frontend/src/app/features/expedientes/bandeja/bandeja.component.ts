import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  HostListener,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  forkJoin,
  map,
  Observable,
  of,
  startWith,
  switchMap,
} from 'rxjs';
import * as XLSX from 'xlsx';

import { ExpedienteService, ExpedienteListParams } from '../expediente.service';
import { CaseNavigationService } from '../case-navigation.service';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { UserAdminService } from '../../../core/auth/user-admin.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { clasificacionLabel, clasificacionTone } from '../../../core/models/clasificacion';
import { formatDate as formatDateUtil } from '../../../core/util/datetime';
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

// Campos por los que GET /api/v1/cases acepta ordenar (propiedades reales de la entidad Case
// en cases-service — Spring Data ordena por propiedad JPA, no por nombre de columna SQL).
type SortField =
  | 'id'
  | 'status'
  | 'insuredName'
  | 'claimCause'
  | 'eventDate'
  | 'claimedAmount'
  | 'riskBand'
  | 'analysisClassification'
  // Path anidado: el analista es una relación, no una columna. Ordenar por apellido es lo que
  // espera quien mira la columna "Analista".
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

/**
 * Lente de pertenencia de la bandeja del analista:
 *  - `mine`       → asignados al usuario logueado (solo analista)
 *  - `all`        → sin recorte por analista
 *  - `assigned`   → con analista, sin importar quién (bandeja del referente)
 *  - `unassigned` → sin analista todavía
 *  - `fraud`      → con alerta de fraude (riesgo alto/crítico)
 */
type Lens = 'mine' | 'all' | 'assigned' | 'unassigned' | 'fraud';

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
    // El buscador de la topbar deriva acá con ?q= al pedir "ver todos los resultados". Se lee en
    // vivo (no solo al montar) porque la bandeja puede ya estar en pantalla cuando se busca.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const q = params.get('q') ?? '';
      if (q !== this.qDraft()) {
        this.qDraft.set(q);
        this.page.set(0);
      }
    });

    // Catálogo real de tipos de siniestro para el filtro (best-effort: si falla, queda vacío).
    this.service.claimCauseNames().subscribe({
      next: (names) => this.claimCauseOptions.set(names.map((n) => ({ value: n, label: n }))),
      error: () => {
        /* backend caído: el filtro queda sin opciones, sin romper la bandeja */
      },
    });

    // El equipo de analistas, para el filtro del referente. El endpoint es solo de ese rol, así
    // que ni se pide para un analista.
    if (this.isReferente()) {
      this.service.analystWorkload().subscribe({
        next: (team) =>
          this.analystOptions.set(
            team.map((a) => ({ value: String(a.analystId), label: a.name })),
          ),
        error: () => {
          /* mismo criterio que arriba: sin opciones, sin romper */
        },
      });
    }
  }

  // ───────────────── Lente: "Míos" vs "Todos" ─────────────────
  // No es un filtro más de la fila de selects: es de quién es el expediente, no cómo se recorta
  // el listado. Por eso vive arriba de la tabla y "Limpiar filtros" no lo toca.
  //
  // Quién es "yo" NO se manda: el id de analista es local al esquema de cada aseguradora, así que
  // lo resuelve el backend contra el token (`assignedToMe`). Acá solo se dice qué lente está
  // activa.

  /** El analista entra a lo suyo; el referente reparte trabajo, así que arranca viendo todo. */
  protected readonly lens = signal<Lens>(
    this.session.session()?.rol === 'ANALISTA_SINIESTROS' ? 'mine' : 'all',
  );

  protected setLens(lens: Lens): void {
    this.lens.set(lens);
    this.page.set(0);
  }

  // ───────────────── Filtros, búsqueda, orden y paginación ─────────────────
  // Todos combinables por AND, reflejan 1:1 los params que acepta GET /api/v1/cases
  // (historia "Búsqueda y filtrado de expedientes").
  protected readonly statusFilter = signal('');
  protected readonly claimCauseFilter = signal('');
  protected readonly riskBandFilter = signal('');
  protected readonly analystFilter = signal('');
  protected readonly eventDateFrom = signal('');
  protected readonly eventDateTo = signal('');
  protected readonly qDraft = signal('');
  protected readonly sortField = signal<SortField>('id');
  protected readonly sortDir = signal<SortDir>('desc');
  protected readonly page = signal(0);
  protected readonly size = signal(10);

  // La búsqueda libre se debounce para no pegarle al backend en cada tecla; el resto de los
  // filtros dispara al toque (son selects/fechas, no texto libre).
  private readonly qDebounced = toSignal(
    toObservable(this.qDraft).pipe(debounceTime(350), distinctUntilChanged()),
    { initialValue: '' },
  );

  // Solo la barra de filtros, SIN la lente. Los conteos del toggle se apoyan en esto porque
  // necesitan pedir las dos lentes sobre la misma base; para "lo que estoy viendo" está
  // viewFilters, que es lo que tienen que usar la tabla y la exportación.
  private readonly activeFilters = computed<ExpedienteListParams>(() => ({
    status: this.statusFilter() || undefined,
    claimCause: this.claimCauseFilter() || undefined,
    riskBand: (this.riskBandFilter() || undefined) as ExpedienteListParams['riskBand'],
    analystId: this.analystFilter() ? Number(this.analystFilter()) : undefined,
    eventDateFrom: this.eventDateFrom() || undefined,
    eventDateTo: this.eventDateTo() || undefined,
    q: this.qDebounced() || undefined,
    sort: `${this.sortField()},${this.sortDir()}`,
  }));

  /** Se incrementa después de asignar/liberar para releer el listado desde el backend. */
  private readonly reloadTrigger = signal(0);

  /**
   * Filtros + lente: la definición completa de "lo que estoy viendo". Única fuente para la tabla
   * y para la exportación — si se bifurcan, el archivo deja de coincidir con la pantalla (la
   * lente quedaba afuera del export y "Míos" exportaba igual todos los expedientes).
   */
  private readonly viewFilters = computed<ExpedienteListParams>(() => ({
    ...this.activeFilters(),
    assignedToMe: this.lens() === 'mine',
    assigned: this.lens() === 'assigned',
    unassigned: this.lens() === 'unassigned',
    fraudAlert: this.lens() === 'fraud',
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

  // La bandeja NO usa la pantalla de carga de marca a viewport completo: esa se reserva al
  // arranque (login → home). Acá la carga se muestra con un spinner en el lugar, sin tapar la
  // pantalla. Se distingue la PRIMERA carga (spinner solo, sin la caja de filtros —queda raro
  // mostrarla vacía) de los refetch por filtro/lente/paginado (los filtros quedan y el spinner
  // reemplaza solo a la tabla).
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

  // Publica el orden visible de la tabla para que el detalle sepa cuál es el expediente
  // anterior/siguiente según ESTE orden (filtros + sort + página), no por id correlativo.
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

  /**
   * Conteo de cada lente para mostrarlo al lado del toggle ("Míos 4 · Todos 57"). Respetan los
   * filtros vigentes: el número tiene que decir cuántos hay *de lo que estás mirando*, no del total
   * absoluto. Un solo request — antes era uno por lente, y cada uno traía una fila entera solo para
   * leerle el total.
   */
  private readonly counts = toSignal(
    toObservable(
      computed(() => ({
        filters: this.activeFilters(),
        reload: this.reloadTrigger(),
      })),
    ).pipe(
      switchMap(({ filters }) =>
        this.service
          .lensSummary(filters)
          .pipe(catchError(() => of({ mine: 0, all: 0, assigned: 0, unassigned: 0, fraud: 0 }))),
      ),
    ),
    { initialValue: { mine: 0, all: 0, assigned: 0, unassigned: 0, fraud: 0 } },
  );

  protected readonly mineCount = computed(() => this.counts().mine);
  protected readonly allCount = computed(() => this.counts().all);
  protected readonly assignedCount = computed(() => this.counts().assigned);
  protected readonly unassignedCount = computed(() => this.counts().unassigned);
  protected readonly fraudCount = computed(() => this.counts().fraud);

  protected readonly hasActiveFilters = computed(
    () =>
      !!(
        this.statusFilter() ||
        this.claimCauseFilter() ||
        this.riskBandFilter() ||
        this.eventDateFrom() ||
        this.eventDateTo() ||
        this.qDebounced()
      ),
  );

  // ───────────────── Catálogos de los selects ─────────────────
  private static readonly STATUS_VALUES: CaseStatus[] = [
    'PENDING_CLASSIFICATION',
    'PENDING_ANALYST_REVIEW',
    'CLASSIFICATION_FAILED',
    'AWAITING_DOCUMENTATION',
    'APPROVED',
    'REJECTED',
  ];

  protected readonly statusOptions: SelectOption[] = BandejaComponent.STATUS_VALUES.map((s) => ({
    value: s,
    label: estadoLabel(s),
  }));

  // Catálogo real de tipos de siniestro (hechos generadores), traído del backend en el constructor:
  // GET /api/v1/claim-causes/all devuelve los nombres distintos de todos los ramos. Antes era una
  // lista hardcodeada con valores que no existían ("Siniestro general") y filtraba vacío.
  protected readonly claimCauseOptions = signal<SelectOption[]>([]);

  /** Equipo de analistas de la aseguradora. Vacío para el analista: el filtro no se le muestra. */
  protected readonly analystOptions = signal<SelectOption[]>([]);

  // Mismas 4 etiquetas que usa app-fraud-gauge para band 1-4, para no inventar un vocabulario
  // paralelo de "nivel de riesgo" entre el filtro y la columna que lo muestra.
  protected readonly riskBandOptions: SelectOption[] = [
    { value: 'LOW', label: 'Bajo' },
    { value: 'MEDIUM', label: 'Medio' },
    { value: 'HIGH', label: 'Alto' },
    { value: 'CRITICAL', label: 'Crítico' },
  ];

  protected readonly columns: ColumnDef[] = [
    { field: 'id', label: 'N°' },
    { field: 'status', label: 'Estado' },
    { field: 'insuredName', label: 'Asegurado' },
    { field: 'claimCause', label: 'Tipo de siniestro' },
    { field: 'eventDate', label: 'Fecha del hecho' },
    { field: 'claimedAmount', label: 'Importe reclamado' },
    { field: 'riskBand', label: 'Riesgo' },
    { field: 'analysisClassification', label: 'Clasificación' },
    { field: 'analyst.surname', label: 'Analista' },
  ];

  // La búsqueda queda fuera del panel (barra siempre visible) y es en vivo (debounced).
  protected onSearchInput(v: string): void {
    this.qDraft.set(v);
    this.page.set(0);
    // Se refleja en la URL para que ?q= no quede desincronizado con lo que se está viendo: si no,
    // buscar de nuevo lo mismo desde la topbar no cambiaría la URL y la bandeja ignoraría el pedido.
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { q: v || null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  // ───────────────── Panel de filtros (drawer lateral, aplicación diferida) ─────────────────
  // Estado/tipo/riesgo/fechas se editan en un panel que se desliza desde la derecha y se aplican
  // al confirmar ("Aplicar filtros"), no en vivo. Los aplicados (statusFilter/…) manejan la tabla;
  // los "draft" son lo que se está editando en el panel (se descartan si se cierra sin aplicar).
  protected readonly filtersOpen = signal(false);
  protected readonly draftStatus = signal('');
  protected readonly draftClaimCause = signal('');
  protected readonly draftRiskBand = signal('');
  protected readonly draftAnalyst = signal('');
  protected readonly draftDateFrom = signal('');
  protected readonly draftDateTo = signal('');

  protected openFilters(): void {
    this.draftStatus.set(this.statusFilter());
    this.draftClaimCause.set(this.claimCauseFilter());
    this.draftRiskBand.set(this.riskBandFilter());
    this.draftAnalyst.set(this.analystFilter());
    this.draftDateFrom.set(this.eventDateFrom());
    this.draftDateTo.set(this.eventDateTo());
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

  // Cierre del popover: click fuera del ancla (el botón + el panel viven dentro de .filters-anchor)
  // o Escape. Mismo enfoque que el kit (menu-button/select), sin backdrop.
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
    this.page.set(0);
    this.filtersOpen.set(false);
  }
  /** Vacía los campos del panel (draft), sin aplicar todavía. */
  protected clearDraft(): void {
    this.draftStatus.set('');
    this.draftClaimCause.set('');
    this.draftRiskBand.set('');
    this.draftAnalyst.set('');
    this.draftDateFrom.set('');
    this.draftDateTo.set('');
  }

  // Cantidad de filtros aplicados (sin contar la búsqueda) → badge del botón "Filtros".
  protected readonly activeFilterCount = computed(() => {
    let n = 0;
    if (this.statusFilter()) n++;
    if (this.claimCauseFilter()) n++;
    if (this.riskBandFilter()) n++;
    if (this.analystFilter()) n++;
    if (this.eventDateFrom()) n++;
    if (this.eventDateTo()) n++;
    return n;
  });

  // Chips de los filtros aplicados: se ven y se quitan sin abrir el panel.
  protected readonly activeChips = computed<{ key: string; label: string }[]>(() => {
    const chips: { key: string; label: string }[] = [];
    if (this.statusFilter())
      chips.push({ key: 'status', label: `Estado: ${estadoLabel(this.statusFilter())}` });
    if (this.claimCauseFilter())
      chips.push({ key: 'claimCause', label: `Tipo: ${this.claimCauseFilter()}` });
    if (this.riskBandFilter())
      chips.push({ key: 'riskBand', label: `Fraude: ${this.riskBandLabel(this.riskBandFilter())}` });
    if (this.analystFilter())
      chips.push({ key: 'analyst', label: `Analista: ${this.analystName(this.analystFilter())}` });
    if (this.eventDateFrom())
      chips.push({ key: 'dateFrom', label: `Desde: ${this.formatDate(this.eventDateFrom())}` });
    if (this.eventDateTo())
      chips.push({ key: 'dateTo', label: `Hasta: ${this.formatDate(this.eventDateTo())}` });
    return chips;
  });

  protected removeChip(key: string): void {
    switch (key) {
      case 'status': this.statusFilter.set(''); break;
      case 'claimCause': this.claimCauseFilter.set(''); break;
      case 'riskBand': this.riskBandFilter.set(''); break;
      case 'analyst': this.analystFilter.set(''); break;
      case 'dateFrom': this.eventDateFrom.set(''); break;
      case 'dateTo': this.eventDateTo.set(''); break;
    }
    this.page.set(0);
  }

  private analystName(id: string): string {
    return this.analystOptions().find((o) => o.value === id)?.label ?? id;
  }

  /** "Limpiar todo": quita todos los filtros aplicados (la búsqueda no se toca). */
  protected clearAllChips(): void {
    this.statusFilter.set('');
    this.claimCauseFilter.set('');
    this.riskBandFilter.set('');
    this.eventDateFrom.set('');
    this.eventDateTo.set('');
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

  // ───────────────── Asignación ─────────────────
  // Asignar es poner dueño, no resolver: el expediente sigue esperando que el analista lo apruebe
  // o lo rechace desde el detalle (human-in-the-loop).

  /** Id del expediente cuya asignación está en vuelo, para deshabilitar el botón mientras tanto. */
  protected readonly assigning = signal<number | null>(null);
  protected readonly assignError = signal<string | null>(null);

  /** Analistas asignables. Se piden una vez; el selector de "Asignar a…" se arma con esto. */
  private readonly analysts = toSignal(this.users.listAnalysts().pipe(catchError(() => of([]))), {
    initialValue: [],
  });

  protected readonly analystMenuItems = computed<MenuItem[]>(() =>
    this.analysts().map((a) => ({ value: String(a.id), label: `${a.nombre} ${a.apellido}` })),
  );

  /**
   * Mi id de analista DENTRO de esta aseguradora. No sale de la sesión —ahí está el id de
   * usuario, que es otra tabla— sino de buscarme por email en el listado de analistas, que ya
   * viene acotado al tenant. Null para el referente, que no tiene perfil de analista.
   */
  private readonly myAnalystId = computed<number | null>(() => {
    const email = this.session.session()?.email;
    return this.analysts().find((a) => a.email === email)?.id ?? null;
  });

  /** Solo un analista puede tomar un expediente para sí; el referente asigna, no se autoasigna. */
  protected readonly canTake = computed(
    () => this.session.session()?.rol === 'ANALISTA_SINIESTROS' && this.myAnalystId() != null,
  );

  /**
   * Solo el analista asigna. El referente ve la bandeja de solo lectura: la columna de analista
   * queda informativa (sin "Asignarme"/"Reasignar"/"Liberar") y sin la lente "Mis asignados",
   * porque no tiene expedientes propios.
   */
  protected readonly canAssign = computed(
    () => this.session.session()?.rol === 'ANALISTA_SINIESTROS',
  );

  /** El referente ve la bandeja de supervisión: sin "Míos", pero con lentes de asignación/fraude. */
  protected readonly isReferente = computed(
    () => this.session.session()?.rol === 'REFERENTE_ASEGURADORA',
  );

  protected isMine(c: ExpedienteResponse): boolean {
    return c.assignedAnalystId != null && c.assignedAnalystId === this.myAnalystId();
  }

  /** Iniciales para el avatar del analista asignado (hasta 2). */
  protected analystInitials(c: ExpedienteResponse): string {
    return (c.assignedAnalystName ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]!.toUpperCase())
      .join('');
  }

  /** Marca del item "Liberar" dentro del menú de acciones — no es un id de analista. */
  private static readonly RELEASE = '__release__';

  /**
   * Items del menú "…": la lista de analistas para reasignar y, si el expediente ya tiene dueño,
   * "Liberar" como acción destructiva separada al final.
   */
  protected assignMenuItems(c: ExpedienteResponse): MenuItem[] {
    // "Asignar a otro analista": el que ya lo tiene no va en la lista (reasignárselo no es una acción).
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

  /** Atajo del analista: se asigna el expediente a sí mismo sin pasar por el selector. */
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
        // Releer del backend en vez de parchear la fila: si la lente es "Míos", el expediente
        // recién liberado tiene que desaparecer del listado y los conteos moverse con él.
        this.reloadTrigger.update((n) => n + 1);
      },
      error: () => {
        this.assigning.set(null);
        this.assignError.set('No se pudo actualizar la asignación. Intentá de nuevo.');
      },
    });
  }

  // ───────────────── Presentación de celdas ─────────────────
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

  // Delega en el util: `new Date('2026-08-20')` se parsea como UTC y en Argentina retrocede al
  // día anterior, que es como una denuncia policial terminaba mostrándose antes del siniestro.
  protected formatDate(value: string): string {
    return formatDateUtil(value);
  }

  protected formatAmount(value: number | null): string {
    return value != null ? `$${value.toLocaleString('es-AR')}` : '—';
  }

  /**
   * "Asegurado" always shows a name — never the DNI in its place. If classification-service
   * hasn't resolved it yet, show an explicit placeholder instead of confusing the DNI for the
   * name (see displayInsuredId for the identifier, which is always available).
   */
  protected displayInsured(c: ExpedienteResponse): string {
    return c.insuredName ?? 'Sin identificar';
  }

  protected displayInsuredId(c: ExpedienteResponse): string {
    return c.insuredId;
  }

  // ───────────────── Exportar (CSV / XLSX) ─────────────────
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

  /**
   * Exporta TODOS los expedientes que matchean lo que se está viendo (no solo la página visible):
   * pagina en secuencia con un tamaño grande hasta agotar totalPages, ignorando la paginación de
   * la tabla. Respeta filtros, lente ("Míos"/"Todos") y orden vigentes; ignora page/size.
   *
   * <p>Sale de {@code viewFilters}, la misma fuente que alimenta la tabla: el archivo tiene que
   * contener exactamente las filas que el analista tiene delante, ni una más.
   */
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
    // BOM al inicio para que Excel abra el UTF-8 sin desarmar tildes/ñ.
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
    // La lente va en el nombre: abierto suelto, el archivo tiene que poder decir si son los
    // expedientes del analista o todos.
    const scope = this.lens() === 'mine' ? 'mios-' : '';
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
