import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of, startWith } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { AnalystWorkload, CaseService } from '../../cases/case.service';
import { CaseResponse } from '../../../core/models/case';
import { caseStatusLabel, caseStatusTone } from '../../../core/models/case-status';
import { StatusTone } from '../../../core/models/status-tone';
import { longDate, greetingForTimeOfDay } from '../../../core/util/datetime';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { StatTileComponent } from '../../../shared/ui/stat-tile/stat-tile.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { LoadingComponent } from '../../../shared/ui/loading/loading.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { AppReadyService } from '../../../core/app-ready.service';
import { growBar, listStagger, staggerReveal } from '../../../shared/animations';

interface Counts {
  active: number;
  resolved: number;
  alerts: number;
  total: number;
}

type CountsState = { status: 'loading' } | { status: 'ok'; counts: Counts } | { status: 'error' };

type AlertState =
  { status: 'loading' } | { status: 'ok'; data: CaseResponse[] } | { status: 'error' };

type WorkloadState =
  { status: 'loading' } | { status: 'ok'; data: AnalystWorkload[] } | { status: 'error' };

@Component({
  selector: 'app-referent-home',
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
  templateUrl: './referent-home.component.html',
  styleUrl: './referent-home.component.scss',
})
export class ReferentHomeComponent {
  private readonly service = inject(CaseService);
  private readonly session = inject(AuthSessionService);
  private readonly appReady = inject(AppReadyService);

  protected readonly greeting = greetingForTimeOfDay();
  protected readonly today = longDate();
  protected readonly firstName = computed(() => this.session.session()?.nombre ?? '');

  // No `assignedToMe`: the referente sees the whole insurer's operation.
  private readonly countsState = toSignal(
    forkJoin({
      total: this.count({}),
      approved: this.count({ status: 'APPROVED' }),
      rejected: this.count({ status: 'REJECTED' }),
      // LAPSED is terminal: count it as resolved, not active.
      lapsed: this.count({ status: 'LAPSED' }),
      high: this.count({ riskBand: 'HIGH' }),
      critical: this.count({ riskBand: 'CRITICAL' }),
    }).pipe(
      map(({ total, approved, rejected, lapsed, high, critical }): CountsState => {
        const resolved = approved + rejected + lapsed;
        return {
          status: 'ok',
          counts: {
            total,
            resolved,
            alerts: high + critical,
            active: Math.max(0, total - resolved),
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
  protected readonly alertItems = computed<CaseResponse[]>(() => {
    const s = this.alertState();
    return s.status === 'ok' ? s.data : [];
  });
  protected readonly alertEmpty = computed(
    () => this.alertState().status === 'ok' && this.alertItems().length === 0,
  );

  private count(params: Parameters<CaseService['list']>[0]) {
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

  protected caseStatusLabel(status: string): string {
    return caseStatusLabel(status);
  }

  protected caseStatusTone(status: string): StatusTone {
    return caseStatusTone(status);
  }

  protected riskLabel(band: string | null): string {
    return band === 'CRITICAL' ? 'Crítico' : band === 'HIGH' ? 'Alto' : '—';
  }

  protected displayInsured(c: CaseResponse): string {
    return c.insuredName ?? 'Sin identificar';
  }
}
