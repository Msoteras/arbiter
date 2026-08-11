import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { estadoLabel, estadoTone } from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { fechaLarga, saludoSegunHora } from '../../../core/util/datetime';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { StatTileComponent } from '../../../shared/ui/stat-tile/stat-tile.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { LoadingComponent } from '../../../shared/ui/loading/loading.component';
import { staggerReveal } from '../../../shared/animations';

interface Counts {
  pendientes: number;
  enTramite: number;
  resueltos: number;
  riesgo: number;
}

type CountsState = { status: 'loading' } | { status: 'ok'; counts: Counts } | { status: 'error' };

type ActionState =
  { status: 'loading' } | { status: 'ok'; data: ExpedienteResponse[] } | { status: 'error' };

/**
 * Pantalla de inicio del analista de siniestros. Los conteos de las tarjetas salen de un endpoint
 * de resumen dedicado (GET /api/v1/cases/assigned/summary, acotado al analista por el token) y la
 * lista "Requieren tu acción" de GET /api/v1/cases. Lo que todavía no tiene backend (SLA/
 * vencimientos, resumen del modelo) se muestra como placeholder honesto, no como número inventado.
 */
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
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal],
  templateUrl: './analista-inicio.component.html',
  styleUrl: './analista-inicio.component.scss',
})
export class AnalistaInicioComponent {
  private readonly service = inject(ExpedienteService);
  private readonly session = inject(AuthSessionService);

  // El saludo y la fecha se fijan al entrar a la pantalla (no hace falta que "tickeen" en vivo).
  protected readonly saludo = saludoSegunHora();
  protected readonly fecha = fechaLarga();
  protected readonly nombre = computed(() => this.session.session()?.nombre ?? '');

  // ───────────────── Conteos del encabezado ─────────────────
  // Una sola llamada al resumen de mis expedientes asignados (el backend resuelve "yo" contra el
  // token y agrupa por estado). "En trámite" = total menos resueltos (APPROVED + REJECTED).
  private readonly countsState = toSignal(
    this.service.assignedSummary().pipe(
      map((s): CountsState => {
        const pendientes = s.byStatus['PENDING_ANALYST_REVIEW'] ?? 0;
        const resueltos = (s.byStatus['APPROVED'] ?? 0) + (s.byStatus['REJECTED'] ?? 0);
        return {
          status: 'ok',
          counts: {
            pendientes,
            resueltos,
            riesgo: s.highRisk,
            // Nunca negativo, por las dudas de un total desalineado con el desglose.
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

  // ───────────────── "Requieren tu acción" ─────────────────
  // Los expedientes propios que esperan la decisión del analista, los que llevan más esperando
  // primero. Se ordena por `reportedAt` (fecha de la denuncia) — la propiedad real de la entidad
  // Case; `eventDate` es el nombre del DTO, no de la entidad, y Spring Data lo rechaza al ordenar.
  private readonly actionState = toSignal(
    this.service
      .list({
        assignedToMe: true,
        status: 'PENDING_ANALYST_REVIEW',
        sort: 'reportedAt,asc',
        size: 5,
      })
      .pipe(
        map((page): ActionState => ({ status: 'ok', data: page.content })),
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

  // La pantalla espera TODOS sus datos: mientras cualquiera de los dos endpoints siga en vuelo se
  // muestra la pantalla de carga de marca, en vez de recuadros con "Cargando…" sueltos.
  protected readonly pageLoading = computed(() => this.countsLoading() || this.actionLoading());

  // ───────────────── Presentación ─────────────────
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
