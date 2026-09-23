import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import {
  EstadoSimplificado,
  estadoBadgeLabelAsegurado,
  estadoDescripcionAsegurado,
  estadoSimplificado,
  estadoSimplificadoLabel,
  estadoTituloAsegurado,
  isEstadoFinal,
} from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { fechaLarga, saludoSegunHora } from '../../../core/util/datetime';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { NewClaimModalService } from '../../expedientes/new-claim-modal.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { LoadingComponent } from '../../../shared/ui/loading/loading.component';
import { staggerReveal } from '../../../shared/animations';

type LoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse[] }
  | { status: 'error' };

type StepState = 'done' | 'active' | 'pending';

interface Step {
  label: string;
  n: number;
  state: StepState;
  /** Only on the resolution step. */
  tone?: StatusTone;
}

const ORDEN: EstadoSimplificado[] = ['DENUNCIADO', 'EN_TRAMITE', 'TERMINADO'];

/** Insured-facing copy: never mention the model's classification or the internal scoring. */
@Component({
  selector: 'app-asegurado-inicio',
  imports: [
    RouterLink,
    CardComponent,
    BadgeComponent,
    ButtonComponent,
    EmptyStateComponent,
    LoadingComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal],
  templateUrl: './asegurado-inicio.component.html',
  styleUrl: './asegurado-inicio.component.scss',
})
export class AseguradoInicioComponent {
  private readonly service = inject(ExpedienteService);
  private readonly session = inject(AuthSessionService);
  protected readonly insured = inject(InsuredSessionService);
  protected readonly newClaim = inject(NewClaimModalService);

  protected readonly saludo = saludoSegunHora();
  protected readonly fecha = fechaLarga();
  protected readonly nombre = computed(() => this.session.session()?.nombre ?? '');

  protected readonly needsIdentity = computed(() => this.insured.insuredId() === null);

  private readonly state = toSignal(
    toObservable(this.insured.insuredId).pipe(
      switchMap((insuredId) => {
        if (!insuredId) {
          return of<LoadState>({ status: 'idle' });
        }
        return this.service.list({ insuredId, page: 0, size: 100 }).pipe(
          map((page): LoadState => ({ status: 'ok', data: page.content })),
          startWith<LoadState>({ status: 'loading' }),
          catchError(() => of<LoadState>({ status: 'error' })),
        );
      }),
    ),
    { initialValue: { status: 'idle' } as LoadState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly hasError = computed(() => this.state().status === 'error');

  protected readonly cases = computed<ExpedienteResponse[]>(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : [];
  });

  protected readonly isEmpty = computed(
    () => this.state().status === 'ok' && this.cases().length === 0,
  );

  // The list comes newest first (default sort id,desc).
  protected readonly destacado = computed<ExpedienteResponse | null>(() => this.cases()[0] ?? null);

  protected readonly enCurso = computed(
    () => this.cases().filter((c) => !isEstadoFinal(c.status)).length,
  );
  protected readonly cerrados = computed(
    () => this.cases().filter((c) => isEstadoFinal(c.status)).length,
  );

  // "En trámite" rather than "En análisis": the phase also covers verification and repair.
  protected readonly steps = computed<Step[]>(() => {
    const d = this.destacado();
    if (!d) {
      return [];
    }
    const idx = ORDEN.indexOf(estadoSimplificado(d.status));
    const stateFor = (threshold: number): StepState =>
      idx > threshold ? 'done' : idx === threshold ? 'active' : 'pending';

    const resuelto = idx >= 2;
    return [
      { label: 'Denuncia recibida', n: 1, state: stateFor(0) },
      { label: 'En trámite', n: 2, state: stateFor(1) },
      {
        label: 'Resolución',
        n: 3,
        // Green only for APPROVED: rejection and lapse are terminal too, but not good news.
        state: resuelto ? 'done' : 'pending',
        tone: resuelto ? (d.status === 'APPROVED' ? 'ok' : 'danger') : undefined,
      },
    ];
  });

  protected connectorDone(i: number): boolean {
    const d = this.destacado();
    if (!d) {
      return false;
    }
    return ORDEN.indexOf(estadoSimplificado(d.status)) > i;
  }

  protected titulo(status: string): string {
    return estadoTituloAsegurado(status);
  }

  protected descripcion(status: string): string {
    return estadoDescripcionAsegurado(status);
  }

  protected estadoSimplificadoLabel(status: string): string {
    return estadoSimplificadoLabel(status);
  }

  // The badge shows the specific status, not the stepper's 3-step bucket.
  protected estadoBadgeLabel(status: string): string {
    return estadoBadgeLabelAsegurado(status);
  }

  protected estadoTone(status: string): StatusTone {
    const simple = estadoSimplificado(status);
    if (simple === 'TERMINADO') {
      return status === 'APPROVED' ? 'ok' : 'danger';
    }
    return 'info';
  }

  protected fechaDenuncia(c: ExpedienteResponse): string {
    return c.createdAt ? new Date(c.createdAt).toLocaleDateString('es-AR') : '—';
  }
}
