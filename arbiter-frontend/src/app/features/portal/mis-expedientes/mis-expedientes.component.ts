import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, debounceTime, distinctUntilChanged, map, of, startWith, switchMap } from 'rxjs';

import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import {
  ESTADOS_SIMPLIFICADOS,
  EstadoSimplificado,
  estadoBadgeLabelAsegurado,
  estadoTone,
  estadosDelCajon,
  isEstadoFinal,
  proximoPaso,
} from '../../../core/models/estado';
import { Policy } from '../../../core/models/policy';
import { StatusTone } from '../../../core/models/status-tone';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { NewClaimModalService } from '../../expedientes/new-claim-modal.service';
import { PolicyService } from '../../expedientes/policy.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { PaginationComponent } from '../../../shared/ui/pagination/pagination.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { listStagger, staggerReveal } from '../../../shared/animations';

type LoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse[]; totalElements: number; totalPages: number }
  | { status: 'error' };

/**
 * Portal del asegurado — sus expedientes, con estado actual y próximo paso.
 * Consume GET /api/v1/cases?insuredId=… (el filtro por asegurado del backend);
 * la identidad sale de InsuredSessionService (stub hasta integrar Auth0).
 */
@Component({
  selector: 'app-mis-expedientes',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    CardComponent,
    BadgeComponent,
    ButtonComponent,
    InputComponent,
    InlineLoadingComponent,
    PaginationComponent,
    SelectComponent,
  ],
  animations: [staggerReveal, listStagger],
  templateUrl: './mis-expedientes.component.html',
  styleUrl: './mis-expedientes.component.scss',
})
export class MisExpedientesComponent {
  private readonly service = inject(ExpedienteService);
  private readonly policyService = inject(PolicyService);
  private readonly newClaim = inject(NewClaimModalService);
  protected readonly session = inject(InsuredSessionService);

  protected readonly identityInput = signal('');

  protected readonly qDraft = signal('');
  protected readonly estadoFilter = signal<EstadoSimplificado | ''>('');
  protected readonly desde = signal('');
  protected readonly hasta = signal('');
  protected readonly insurerFilter = signal('');
  protected readonly page = signal(0);
  protected readonly size = signal(10);

  protected readonly estadoOptions: SelectOption[] = ESTADOS_SIMPLIFICADOS;

  private readonly qDebounced = toSignal(
    toObservable(this.qDraft).pipe(debounceTime(350), distinctUntilChanged()),
    { initialValue: '' },
  );

  /**
   * Las compañías salen de sus pólizas y no de los expedientes ya cargados: con la vista paginada,
   * la página que estás mirando puede no tener ninguno de la otra.
   */
  private readonly policies = toSignal(
    toObservable(this.session.insuredId).pipe(
      switchMap((insuredId) =>
        insuredId ? this.policies$(insuredId) : of<Policy[]>([]),
      ),
    ),
    { initialValue: [] as Policy[] },
  );

  protected readonly insurerOptions = computed<SelectOption[]>(() => {
    const byId = new Map(this.policies().map((p) => [p.insurerId, p.insurerName]));
    return [...byId].map(([value, label]) => ({ value, label }));
  });

  /** Con una sola compañía el filtro es ruido: todos los siniestros son de ella. */
  protected readonly showInsurerFilter = computed(() => this.insurerOptions().length > 1);

  private readonly params = computed(() => ({
    insuredId: this.session.insuredId(),
    q: this.qDebounced() || undefined,
    status: this.estadoFilter() ? estadosDelCajon(this.estadoFilter() as EstadoSimplificado) : undefined,
    eventDateFrom: this.desde() || undefined,
    eventDateTo: this.hasta() || undefined,
    insurerId: this.insurerFilter() ? Number(this.insurerFilter()) : undefined,
    page: this.page(),
    size: this.size(),
  }));

  private readonly state = toSignal(
    toObservable(this.params).pipe(
      switchMap(({ insuredId, ...filters }) => {
        if (!insuredId) {
          return of<LoadState>({ status: 'idle' });
        }
        return this.service.list({ insuredId, ...filters }).pipe(
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
        );
      }),
    ),
    { initialValue: { status: 'idle' } as LoadState },
  );

  // Con las vencidas: el filtro recorta siniestros históricos, y una póliza que venció el año
  // pasado dejó siniestros que siguen en la lista.
  private policies$(insuredId: string) {
    return this.policyService
      .listByInsured(insuredId, true)
      .pipe(catchError(() => of<Policy[]>([])));
  }

  protected readonly needsIdentity = computed(() => this.session.insuredId() === null);
  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly hasError = computed(() => this.state().status === 'error');

  protected readonly cases = computed<ExpedienteResponse[]>(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : [];
  });

  protected readonly isEmpty = computed(
    () => this.state().status === 'ok' && this.cases().length === 0,
  );

  protected readonly totalElements = computed(() => {
    const s = this.state();
    return s.status === 'ok' ? s.totalElements : 0;
  });

  protected readonly totalPages = computed(() => {
    const s = this.state();
    return s.status === 'ok' ? s.totalPages : 0;
  });

  protected readonly hasFilters = computed(
    () =>
      !!(
        this.qDebounced() ||
        this.estadoFilter() ||
        this.desde() ||
        this.hasta() ||
        this.insurerFilter()
      ),
  );

  protected onFilterChange(): void {
    this.page.set(0);
  }

  protected clearFilters(): void {
    this.qDraft.set('');
    this.estadoFilter.set('');
    this.desde.set('');
    this.hasta.set('');
    this.insurerFilter.set('');
    this.page.set(0);
  }

  protected onPageChange(page: number): void {
    this.page.set(page);
  }

  protected onSizeChange(size: number): void {
    this.size.set(size);
    this.page.set(0);
  }

  protected identify(): void {
    this.session.identify(this.identityInput());
  }

  protected nuevaDenuncia(): void {
    this.newClaim.open();
  }

  protected estadoLabel(status: string): string {
    return estadoBadgeLabelAsegurado(status);
  }

  protected estadoTone(status: string): StatusTone {
    return estadoTone(status);
  }

  protected proximoPaso(status: string): string {
    return proximoPaso(status);
  }

  protected isFinal(status: string): boolean {
    return isEstadoFinal(status);
  }

  protected fechaDenuncia(c: ExpedienteResponse): string {
    return c.createdAt ? new Date(c.createdAt).toLocaleDateString('es-AR') : '—';
  }

  /** La que filtra el rango de fechas, así que tiene que estar a la vista. */
  protected fechaHecho(c: ExpedienteResponse): string {
    return c.eventDate ? new Date(c.eventDate).toLocaleDateString('es-AR') : '—';
  }
}
