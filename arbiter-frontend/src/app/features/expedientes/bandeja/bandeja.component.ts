import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
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

import { ExpedienteService, ExpedienteListParams } from '../expediente.service';
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
  | 'analysisClassification';
type SortDir = 'asc' | 'desc';

interface ColumnDef {
  field: SortField;
  label: string;
}

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse[]; totalElements: number; totalPages: number }
  | { status: 'error' };

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
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './bandeja.component.html',
  styleUrl: './bandeja.component.scss',
})
export class BandejaComponent {
  private readonly service = inject(ExpedienteService);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);

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

  // Filtros compartidos por la vista paginada y por la exportación a CSV (que ignora
  // page/size propios y trae todas las páginas que matcheen).
  private readonly activeFilters = computed<ExpedienteListParams>(() => ({
    status: this.statusFilter() || undefined,
    claimCause: this.claimCauseFilter() || undefined,
    riskBand: (this.riskBandFilter() || undefined) as ExpedienteListParams['riskBand'],
    eventDateFrom: this.eventDateFrom() || undefined,
    eventDateTo: this.eventDateTo() || undefined,
    q: this.qDebounced() || undefined,
    sort: `${this.sortField()},${this.sortDir()}`,
  }));

  private readonly requestParams = computed<ExpedienteListParams>(() => ({
    ...this.activeFilters(),
    page: this.page(),
    size: this.size(),
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

  // ───────────────── Exportar CSV ─────────────────
  protected readonly exportingCsv = signal(false);

  private static readonly CSV_HEADER = [
    'N°',
    'Estado',
    'Asegurado',
    'N° de póliza',
    'Tipo de siniestro',
    'Fecha del hecho',
    'Importe reclamado',
    'Riesgo',
    'Clasificación',
  ];

  /**
   * Exporta TODOS los expedientes que matchean los filtros actuales (no solo la página visible):
   * pagina en secuencia con un tamaño grande hasta agotar totalPages, ignorando la paginación de
   * la tabla. Respeta filtros + orden vigentes; ignora page/size de la vista.
   */
  protected exportCsv(): void {
    if (this.exportingCsv()) {
      return;
    }
    this.exportingCsv.set(true);

    const params: ExpedienteListParams = { ...this.activeFilters(), size: 200 };

    this.fetchAllPages(params, 0, []).subscribe({
      next: (rows) => {
        this.downloadCsv(rows);
        this.exportingCsv.set(false);
      },
      error: () => {
        this.exportingCsv.set(false);
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
      BandejaComponent.CSV_HEADER.join(','),
      ...rows.map((r) => this.toCsvRow(r)),
    ];
    // BOM al inicio para que Excel abra el UTF-8 sin desarmar tildes/ñ.
    const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);

    const link = this.document.createElement('a');
    link.href = url;
    link.download = `expedientes-${this.timestampForFilename()}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  }

  private toCsvRow(c: ExpedienteResponse): string {
    const cells = [
      String(c.id),
      estadoLabel(c.status),
      this.displayInsured(c),
      c.policyNumber,
      c.claimCause,
      this.formatDate(c.eventDate),
      c.claimedAmount != null ? String(c.claimedAmount) : '',
      this.riskBandLabel(c.riskBand),
      c.analysisClassification ? clasificacionLabel(c.analysisClassification) : '',
    ];
    return cells.map((v) => this.csvEscape(v)).join(',');
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
