import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import {
  EstadoSimplificado,
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

/** Estado visual de un nodo del stepper de 3 pasos. */
type StepState = 'done' | 'active' | 'pending';

interface Step {
  label: string;
  n: number;
  state: StepState;
  /** Sólo el nodo de resolución: 'ok' si se aprobó, 'danger' si se rechazó. */
  tone?: StatusTone;
}

// Orden del progreso simplificado que ve el asegurado. Espejo del que usa el modelo de estado.
const ORDEN: EstadoSimplificado[] = ['DENUNCIADO', 'EN_TRAMITE', 'TERMINADO'];

/**
 * Pantalla de inicio del asegurado. Portal liviano centrado en "cómo va lo mío": pone al frente
 * el expediente activo con un stepper simple (Denuncia recibida → En análisis → Resolución), el
 * próximo paso, y accesos a nueva denuncia / historial / ayuda. Todo el copy es asegurado-safe:
 * nunca menciona la clasificación del modelo ni el scoring interno.
 */
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

  // El listado viene más reciente primero (sort id,desc por defecto): el primero es el siniestro
  // que el asegurado más probablemente quiere ver al entrar.
  protected readonly destacado = computed<ExpedienteResponse | null>(() => this.cases()[0] ?? null);

  // Contadores para la tarjeta "Mis expedientes" ("N en curso · M cerrado").
  protected readonly enCurso = computed(
    () => this.cases().filter((c) => !isEstadoFinal(c.status)).length,
  );
  protected readonly cerrados = computed(
    () => this.cases().filter((c) => isEstadoFinal(c.status)).length,
  );

  // ───────────────── Stepper de 3 pasos ─────────────────
  // Denuncia recibida → En análisis → Resolución, derivado del estado simplificado del destacado.
  protected readonly steps = computed<Step[]>(() => {
    const d = this.destacado();
    if (!d) {
      return [];
    }
    const idx = ORDEN.indexOf(estadoSimplificado(d.status)); // 0, 1 o 2
    const stateFor = (threshold: number): StepState =>
      idx > threshold ? 'done' : idx === threshold ? 'active' : 'pending';

    const resuelto = idx >= 2;
    return [
      { label: 'Denuncia recibida', n: 1, state: stateFor(0) },
      { label: 'En análisis', n: 2, state: stateFor(1) },
      {
        label: 'Resolución',
        n: 3,
        // El último nodo se marca "done" cuando el caso se resolvió, con el tono del desenlace.
        // Verde solo si el siniestro se aprobó: los otros dos desenlaces terminales (rechazo y
        // caducidad) no son buenas noticias, y pintar un caducado de verde le decía al asegurado
        // lo contrario de lo que pasó.
        state: resuelto ? 'done' : 'pending',
        tone: resuelto ? (d.status === 'APPROVED' ? 'ok' : 'danger') : undefined,
      },
    ];
  });

  /** True si el conector entre el paso `i` y el `i+1` ya está recorrido (tramo teal). */
  protected connectorDone(i: number): boolean {
    const d = this.destacado();
    if (!d) {
      return false;
    }
    return ORDEN.indexOf(estadoSimplificado(d.status)) > i;
  }

  // ───────────────── Presentación (copy asegurado-safe) ─────────────────
  protected titulo(status: string): string {
    return estadoTituloAsegurado(status);
  }

  protected descripcion(status: string): string {
    return estadoDescripcionAsegurado(status);
  }

  protected estadoSimplificadoLabel(status: string): string {
    return estadoSimplificadoLabel(status);
  }

  protected estadoTone(status: string): StatusTone {
    const simple = estadoSimplificado(status);
    if (simple === 'TERMINADO') {
      // Mismo criterio que el tono del stepper: verde solo para APPROVED.
      return status === 'APPROVED' ? 'ok' : 'danger';
    }
    return 'info';
  }

  protected fechaDenuncia(c: ExpedienteResponse): string {
    return c.createdAt ? new Date(c.createdAt).toLocaleDateString('es-AR') : '—';
  }
}
