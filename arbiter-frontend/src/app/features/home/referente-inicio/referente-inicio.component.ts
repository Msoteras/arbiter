import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
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
import { staggerReveal } from '../../../shared/animations';

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

/**
 * Pantalla de inicio del referente de aseguradora — panorama de la operación. Los conteos y la
 * carga del equipo salen de endpoints reales del cases-service (acotados al tenant por el backend).
 * Las métricas agregadas que todavía no tienen backend (tiempo promedio de resolución, mix de
 * estados) viven en el dashboard, que es su maqueta viva; desde acá se enlaza, no se duplican.
 */
@Component({
  selector: 'app-referente-inicio',
  imports: [
    RouterLink,
    CardComponent,
    StatTileComponent,
    BadgeComponent,
    EmptyStateComponent,
    LoadingComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal],
  templateUrl: './referente-inicio.component.html',
  styleUrl: './referente-inicio.component.scss',
})
export class ReferenteInicioComponent {
  private readonly service = inject(ExpedienteService);
  private readonly session = inject(AuthSessionService);

  protected readonly saludo = saludoSegunHora();
  protected readonly fecha = fechaLarga();
  protected readonly nombre = computed(() => this.session.session()?.nombre ?? '');

  // ───────────────── Conteos ─────────────────
  // Sin `assignedToMe`: el referente ve toda la operación de la aseguradora, no una bandeja propia.
  private readonly countsState = toSignal(
    forkJoin({
      total: this.count({}),
      aprobados: this.count({ status: 'APPROVED' }),
      rechazados: this.count({ status: 'REJECTED' }),
      alto: this.count({ riskBand: 'HIGH' }),
      critico: this.count({ riskBand: 'CRITICAL' }),
    }).pipe(
      map(({ total, aprobados, rechazados, alto, critico }): CountsState => {
        const resueltos = aprobados + rechazados;
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

  // ───────────────── Expedientes con alerta de fraude ─────────────────
  // Críticos primero, luego altos, hasta 5. Es la lista donde el referente enfoca su atención.
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

  // ───────────────── Carga del equipo ─────────────────
  // Cada analista con su cantidad de expedientes activos, del endpoint dedicado del cases-service.
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

  // La pantalla espera TODOS sus datos: se muestra la pantalla de carga hasta que los tres
  // endpoints (conteos, alertas y carga del equipo) hayan respondido.
  protected readonly pageLoading = computed(
    () => this.countsLoading() || this.alertLoading() || this.workloadLoading(),
  );

  // Tope para escalar las barras. Piso en 1 para no dividir por cero cuando nadie tiene carga.
  private readonly maxLoad = computed(() =>
    Math.max(1, ...this.workload().map((a) => a.activeCases)),
  );

  /** Ancho de la barra de un analista, en % del más cargado. */
  protected barPct(activeCases: number): number {
    return Math.round((activeCases / this.maxLoad()) * 100);
  }

  // ───────────────── Presentación ─────────────────
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
