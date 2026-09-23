import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of, startWith } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { AnalystWorkload, ExpedienteService } from '../../expedientes/expediente.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { estadoLabel, estadoTone } from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { fechaLarga, saludoSegunHora } from '../../../core/util/datetime';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { StatTileComponent } from '../../../shared/ui/stat-tile/stat-tile.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { LoadingComponent } from '../../../shared/ui/loading/loading.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { AppReadyService } from '../../../core/app-ready.service';
import { growBar, listStagger, staggerReveal } from '../../../shared/animations';

interface Counts {
  activos: number;
  resueltos: number;
  alertas: number;
  total: number;
}

type CountsState = { status: 'loading' } | { status: 'ok'; counts: Counts } | { status: 'error' };

type AlertState =
  { status: 'loading' } | { status: 'ok'; data: ExpedienteResponse[] } | { status: 'error' };

type WorkloadState =
  { status: 'loading' } | { status: 'ok'; data: AnalystWorkload[] } | { status: 'error' };

@Component({
  selector: 'app-referente-inicio',
  imports: [
    RouterLink,
    CardComponent,
    StatTileComponent,
    BadgeComponent,
    EmptyStateComponent,
    LoadingComponent,
    InlineLoadingComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal, listStagger, growBar],
  templateUrl: './referente-inicio.component.html',
  styleUrl: './referente-inicio.component.scss',
})
export class ReferenteInicioComponent {
  private readonly service = inject(ExpedienteService);
  private readonly session = inject(AuthSessionService);
  private readonly appReady = inject(AppReadyService);

  protected readonly saludo = saludoSegunHora();
  protected readonly fecha = fechaLarga();
  protected readonly nombre = computed(() => this.session.session()?.nombre ?? '');

  // No `assignedToMe`: the referente sees the whole insurer's operation.
  private readonly countsState = toSignal(
    forkJoin({
      total: this.count({}),
      aprobados: this.count({ status: 'APPROVED' }),
      rechazados: this.count({ status: 'REJECTED' }),
      // LAPSED is terminal: count it as resolved, not active.
      caducados: this.count({ status: 'LAPSED' }),
      alto: this.count({ riskBand: 'HIGH' }),
      critico: this.count({ riskBand: 'CRITICAL' }),
    }).pipe(
      map(({ total, aprobados, rechazados, caducados, alto, critico }): CountsState => {
        const resueltos = aprobados + rechazados + caducados;
        return {
          status: 'ok',
          counts: {
            total,
            resueltos,
            alertas: alto + critico,
            activos: Math.max(0, total - resueltos),
          },
        };
      }),
      startWith<CountsState>({ status: 'loading' }),
      catchError(() => of<CountsState>({ status: 'error' })),
    ),
    { initialValue: { status: 'loading' } as CountsState },
  );

  protected readonly countsLoading = computed(() => this.countsState().status === 'loading');
  protected readonly counts = computed<Counts | null>(() => {
    const s = this.countsState();
    return s.status === 'ok' ? s.counts : null;
  });

  private readonly alertState = toSignal(
    forkJoin({
      critico: this.service.list({ riskBand: 'CRITICAL', sort: 'id,desc', size: 5 }),
      alto: this.service.list({ riskBand: 'HIGH', sort: 'id,desc', size: 5 }),
    }).pipe(
      map(({ critico, alto }): AlertState => ({
        status: 'ok',
        data: [...critico.content, ...alto.content].slice(0, 5),
      })),
      startWith<AlertState>({ status: 'loading' }),
      catchError(() => of<AlertState>({ status: 'error' })),
    ),
    { initialValue: { status: 'loading' } as AlertState },
  );

  protected readonly alertLoading = computed(() => this.alertState().status === 'loading');
  protected readonly alertError = computed(() => this.alertState().status === 'error');
  protected readonly alertItems = computed<ExpedienteResponse[]>(() => {
    const s = this.alertState();
    return s.status === 'ok' ? s.data : [];
  });
  protected readonly alertEmpty = computed(
    () => this.alertState().status === 'ok' && this.alertItems().length === 0,
  );

  private count(params: Parameters<ExpedienteService['list']>[0]) {
    return this.service.list({ ...params, page: 0, size: 1 }).pipe(map((p) => p.totalElements));
  }

  private readonly workloadState = toSignal(
    this.service.analystWorkload().pipe(
      map((data): WorkloadState => ({ status: 'ok', data })),
      startWith<WorkloadState>({ status: 'loading' }),
      catchError(() => of<WorkloadState>({ status: 'error' })),
    ),
    { initialValue: { status: 'loading' } as WorkloadState },
  );

  protected readonly workloadLoading = computed(() => this.workloadState().status === 'loading');
  protected readonly workloadError = computed(() => this.workloadState().status === 'error');
  protected readonly workload = computed<AnalystWorkload[]>(() => {
    const s = this.workloadState();
    return s.status === 'ok' ? s.data : [];
  });
  protected readonly workloadEmpty = computed(
    () => this.workloadState().status === 'ok' && this.workload().length === 0,
  );

  protected readonly pageLoading = computed(
    () => this.countsLoading() || this.alertLoading() || this.workloadLoading(),
  );

  // The full-viewport loader shows only on app startup; later visits use an inline spinner.
  protected readonly showFullLoader = computed(() => this.pageLoading() && !this.appReady.ready());
  private readonly markReady = effect(() => {
    if (!this.pageLoading()) {
      this.appReady.markReady();
    }
  });

  // Floor of 1 avoids dividing by zero when nobody has cases.
  private readonly maxLoad = computed(() =>
    Math.max(1, ...this.workload().map((a) => a.activeCases)),
  );

  protected barPct(activeCases: number): number {
    return Math.round((activeCases / this.maxLoad()) * 100);
  }

  protected estadoLabel(status: string): string {
    return estadoLabel(status);
  }

  protected estadoTone(status: string): StatusTone {
    return estadoTone(status);
  }

  protected riskLabel(band: string | null): string {
    return band === 'CRITICAL' ? 'Crítico' : band === 'HIGH' ? 'Alto' : '—';
  }

  protected displayInsured(c: ExpedienteResponse): string {
    return c.insuredName ?? 'Sin identificar';
  }
}
