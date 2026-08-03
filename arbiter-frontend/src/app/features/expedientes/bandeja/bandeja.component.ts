import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
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
import { CaseStatus, estadoLabel, estadoTone } from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { PaginationComponent } from '../../../shared/ui/pagination/pagination.component';
import { FraudGaugeComponent } from '../../../shared/ui/fraud-gauge/fraud-gauge.component';
import { MenuButtonComponent, MenuItem } from '../../../shared/ui/menu-button/menu-button.component';

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
  | 'assignedAnalystName';
type SortDir = 'asc' | 'desc';

interface ColumnDef {
  field: SortField;
  label: string;
}

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse[]; totalElements: number; totalPages: number }
  | { status: 'error' };

/** "Míos" = expedientes asignados al usuario logueado; "Todos" = sin recorte por analista. */
type Lens = 'mine' | 'all';

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
    MenuButtonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
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

  // ───────────────── Lente: "Míos" vs "Todos" ─────────────────
  // No es un filtro más de la fila de selects: es de quién es el expediente, no cómo se recorta
  // el listado. Por eso vive arriba de la tabla y "Limpiar filtros" no lo toca.
  private readonly myId = computed(() => this.session.session()?.id ?? null);

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
  protected readonly eventDateFrom = signal('');
  protected readonly eventDateTo = signal('');
  protected readonly qDraft = signal('');
  protected readonly sortField = signal<SortField>('id');
  protected readonly sortDir = signal<SortDir>('desc');
  protected readonly page = signal(0);
  protected readonly size = signal(20);

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
    eventDateFrom: this.eventDateFrom() || undefined,
    eventDateTo: this.eventDateTo() || undefined,
    q: this.qDebounced() || undefined,
    sort: `${this.sortField()},${this.sortDir()}`,
  }));

  /** Se incrementa después de asignar/liberar para releer el listado desde el backend. */
  private readonly reloadTrigger = signal(0);

  /** `undefined` = lente "Todos" (el service no manda el param). */
  private readonly lensAnalystId = computed<number | undefined>(() =>
    this.lens() === 'mine' ? (this.myId() ?? undefined) : undefined,
  );

  /**
   * Filtros + lente: la definición completa de "lo que estoy viendo". Única fuente para la tabla
   * y para la exportación — si se bifurcan, el archivo deja de coincidir con la pantalla (la
   * lente quedaba afuera del export y "Míos" exportaba igual todos los expedientes).
   */
  private readonly viewFilters = computed<ExpedienteListParams>(() => ({
    ...this.activeFilters(),
    assignedAnalystId: this.lensAnalystId(),
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
          map(
            (page): LoadState => ({
              status: 'ok',
              data: page.content,
              totalElements: page.totalElements,
              totalPages: page.totalPages,
            }),
          ),
          startWith<LoadState>({ status: 'loading' }),
          catchError(() => of<LoadState>({ status: 'error' })),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as LoadState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly hasError = computed(() => this.state().status === 'error');

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
   * Conteo de cada lente para mostrarlo al lado del toggle ("Míos 4 · Todos 57"). Se piden con
   * `size: 1` porque solo interesa `totalElements`, no las filas. Respetan los filtros vigentes:
   * el número tiene que decir cuántos hay *de lo que estás mirando*, no del total absoluto.
   */
  private readonly counts = toSignal(
    toObservable(
      computed(() => ({
        filters: this.activeFilters(),
        myId: this.myId(),
        reload: this.reloadTrigger(),
      })),
    ).pipe(
      switchMap(({ filters, myId }) =>
        forkJoin({
          mine:
            myId == null
              ? of(0)
              : this.service
                  .list({ ...filters, assignedAnalystId: myId, page: 0, size: 1 })
                  .pipe(map((p) => p.totalElements)),
          all: this.service
            .list({ ...filters, page: 0, size: 1 })
            .pipe(map((p) => p.totalElements)),
        }).pipe(catchError(() => of({ mine: 0, all: 0 }))),
      ),
    ),
    { initialValue: { mine: 0, all: 0 } },
  );

  protected readonly mineCount = computed(() => this.counts().mine);
  protected readonly allCount = computed(() => this.counts().all);

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

  // Catálogo de tipos de siniestro conocidos (mismo set que usa nueva-denuncia.component.ts
  // para el alta). Temporal: HechoGenerador es dueño de rules-service (ver CLAUDE.md, "Modelo
  // de dominio"), que todavía no expone un endpoint de catálogo — hasta entonces, match exacto
  // contra este set corto hardcodeado.
  protected readonly claimCauseOptions: SelectOption[] = [
    { value: 'Robo en vía pública', label: 'Robo en vía pública' },
    { value: 'Hurto', label: 'Hurto' },
    { value: 'Rotura accidental', label: 'Rotura accidental' },
    { value: 'Siniestro general', label: 'Siniestro general' },
  ];

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
    { field: 'assignedAnalystName', label: 'Analista' },
  ];

  // ───────────────── Handlers de filtros (todos resetean a página 0) ─────────────────
  protected onStatusChange(v: string): void {
    this.statusFilter.set(v);
    this.page.set(0);
  }

  protected onClaimCauseChange(v: string): void {
    this.claimCauseFilter.set(v);
    this.page.set(0);
  }

  protected onRiskBandChange(v: string): void {
    this.riskBandFilter.set(v);
    this.page.set(0);
  }

  protected onDateFromChange(v: string): void {
    this.eventDateFrom.set(v);
    this.page.set(0);
  }

  protected onDateToChange(v: string): void {
    this.eventDateTo.set(v);
    this.page.set(0);
  }

  protected onSearchInput(v: string): void {
    this.qDraft.set(v);
    this.page.set(0);
  }

  protected clearFilters(): void {
    this.statusFilter.set('');
    this.claimCauseFilter.set('');
    this.riskBandFilter.set('');
    this.eventDateFrom.set('');
    this.eventDateTo.set('');
    this.qDraft.set('');
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
  private readonly analysts = toSignal(
    this.users.listAnalysts().pipe(catchError(() => of([]))),
    { initialValue: [] },
  );

  protected readonly analystMenuItems = computed<MenuItem[]>(() =>
    this.analysts().map((a) => ({ value: String(a.id), label: `${a.nombre} ${a.apellido}` })),
  );

  /** Solo un analista puede tomar un expediente para sí; el referente asigna, no se autoasigna. */
  protected readonly canTake = computed(
    () => this.session.session()?.rol === 'ANALISTA_SINIESTROS' && this.myId() != null,
  );

  protected isMine(c: ExpedienteResponse): boolean {
    return c.assignedAnalystId != null && c.assignedAnalystId === this.myId();
  }

  protected displayAnalyst(c: ExpedienteResponse): string {
    return c.assignedAnalystName ?? 'Sin asignar';
  }

  /** Atajo del analista: se asigna el expediente a sí mismo sin pasar por el selector. */
  protected take(c: ExpedienteResponse): void {
    const me = this.myId();
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

  protected formatDate(value: string): string {
    return new Date(value).toLocaleDateString('es-AR');
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
      ...rows.map((r) => this.toRowCells(r).map((v) => this.csvEscape(v)).join(',')),
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
