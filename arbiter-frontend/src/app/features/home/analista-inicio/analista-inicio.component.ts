import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { ESTADOS_FINALES, estadoLabel, estadoTone } from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { fechaLarga, saludoSegunHora } from '../../../core/util/datetime';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { StatTileComponent } from '../../../shared/ui/stat-tile/stat-tile.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { LoadingComponent } from '../../../shared/ui/loading/loading.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { AppReadyService } from '../../../core/app-ready.service';
import { growBar, listStagger, staggerReveal } from '../../../shared/animations';

interface Counts {
  pendientes: number;
  enTramite: number;
  resueltos: number;
  riesgo: number;
}

interface DistSegment {
  label: string;
  value: number;
  tone: 'info' | 'warning' | 'ok';
}

type CountsState = { status: 'loading' } | { status: 'ok'; counts: Counts } | { status: 'error' };

type ActionState =
  { status: 'loading' } | { status: 'ok'; data: ExpedienteResponse[] } | { status: 'error' };

@Component({
  selector: 'app-analista-inicio',
  imports: [
    RouterLink,
    CardComponent,
    StatTileComponent,
    BadgeComponent,
    ButtonComponent,
    EmptyStateComponent,
    LoadingComponent,
    InlineLoadingComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal, listStagger, growBar],
  templateUrl: './analista-inicio.component.html',
  styleUrl: './analista-inicio.component.scss',
})
export class AnalistaInicioComponent {
  private readonly service = inject(ExpedienteService);
  private readonly session = inject(AuthSessionService);
  private readonly appReady = inject(AppReadyService);

  protected readonly saludo = saludoSegunHora();
  protected readonly fecha = fechaLarga();
  protected readonly nombre = computed(() => this.session.session()?.nombre ?? '');

  private readonly countsState = toSignal(
    this.service.assignedSummary().pipe(
      map((s): CountsState => {
        // Awaiting the referente's sign-off: still under review, but the analyst already decided.
        const pendientes = Math.max(
          0,
          (s.byStatus['PENDING_ANALYST_REVIEW'] ?? 0) - s.awaitingReferent,
        );
        const resueltos = ESTADOS_FINALES.reduce((acc, e) => acc + (s.byStatus[e] ?? 0), 0);
        return {
          status: 'ok',
          counts: {
            pendientes,
            resueltos,
            riesgo: s.highRisk,
            enTramite: Math.max(0, s.total - resueltos),
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

  // Sort by the entity property `reportedAt`: the DTO's `eventDate` is rejected by Spring Data.
  private readonly actionState = toSignal(
    this.service
      .list({
        assignedToMe: true,
        status: 'PENDING_ANALYST_REVIEW',
        sort: 'reportedAt,asc',
        // Some may be awaiting the referente and get dropped below, hence more than 5.
        size: 15,
      })
      .pipe(
        map((page): ActionState => ({
          status: 'ok',
          data: page.content
            .filter((c) => c.settlementStatus !== 'PENDING_AUTHORIZATION')
            .slice(0, 5),
        })),
        startWith<ActionState>({ status: 'loading' }),
        catchError(() => of<ActionState>({ status: 'error' })),
      ),
    { initialValue: { status: 'loading' } as ActionState },
  );

  protected readonly actionLoading = computed(() => this.actionState().status === 'loading');
  protected readonly actionError = computed(() => this.actionState().status === 'error');
  protected readonly actionItems = computed<ExpedienteResponse[]>(() => {
    const s = this.actionState();
    return s.status === 'ok' ? s.data : [];
  });
  protected readonly actionEmpty = computed(
    () => this.actionState().status === 'ok' && this.actionItems().length === 0,
  );

  protected readonly pageLoading = computed(() => this.countsLoading() || this.actionLoading());

  // The full-viewport loader shows only on app startup; later visits use an inline spinner.
  protected readonly showFullLoader = computed(() => this.pageLoading() && !this.appReady.ready());
  private readonly markReady = effect(() => {
    if (!this.pageLoading()) {
      this.appReady.markReady();
    }
  });

  protected readonly distSegments = computed<DistSegment[]>(() => {
    const c = this.counts();
    if (!c) return [];
    // Segments must be disjoint: `enTramite` already includes `pendientes`, and high-risk
    // overlaps every category, so it's shown apart.
    return [
      { label: 'Pendientes', value: c.pendientes, tone: 'info' },
      {
        label: 'Otros en trámite',
        value: Math.max(0, c.enTramite - c.pendientes),
        tone: 'warning',
      },
      { label: 'Resueltos', value: c.resueltos, tone: 'ok' },
    ];
  });

  protected readonly distTotal = computed(() =>
    this.distSegments().reduce((sum, segment) => sum + segment.value, 0),
  );
  protected readonly hasCaseload = computed(() => this.distTotal() > 0);
  protected readonly riesgo = computed(() => this.counts()?.riesgo ?? 0);

  protected distPct(value: number): number {
    const total = this.distTotal();
    return total > 0 ? Math.round((value / total) * 100) : 0;
  }

  protected estadoLabel(status: string): string {
    return estadoLabel(status);
  }

  protected estadoTone(status: string): StatusTone {
    return estadoTone(status);
  }

  protected displayInsured(c: ExpedienteResponse): string {
    return c.insuredName ?? 'Sin identificar';
  }
}
