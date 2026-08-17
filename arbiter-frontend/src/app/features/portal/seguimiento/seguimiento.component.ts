import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';

import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import {
  EstadoSimplificado,
  estadoDescripcionAseguradoEfectivo,
  estadoLabel,
  estadoSimplificado,
  estadoSimplificadoEfectivo,
  estadoTituloAseguradoEfectivo,
  estadoTone,
  isEstadoFinal,
} from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDateTime } from '../../../core/util/datetime';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CaseDocumentsComponent } from '../../expedientes/case-documents/case-documents.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse }
  | { status: 'error'; httpStatus: number };

interface Milestone {
  stage: EstadoSimplificado;
  label: string;
  date: string | null;
  reached: boolean;
  current: boolean;
}

const SIMPLIFICADO_LABEL: Record<EstadoSimplificado, string> = {
  DENUNCIADO: 'Denuncia recibida',
  EN_TRAMITE: 'En trámite',
  TERMINADO: 'Terminado',
};

/**
 * Seguimiento de un expediente para el asegurado: hero con estado tranquilizador,
 * acción requerida cuando falta documentación, y timeline de trazabilidad. A
 * diferencia de la vista del analista, acá NO se muestra la recomendación del modelo
 * — el asegurado ve estado y próximos pasos; la clasificación es insumo del analista.
 * La carga de documentación vive en su propia pantalla (portal/cases/:id/documentacion).
 */
@Component({
  selector: 'app-seguimiento',
  imports: [RouterLink, CardComponent, ButtonComponent, CaseDocumentsComponent, InlineLoadingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './seguimiento.component.html',
  styleUrl: './seguimiento.component.scss',
})
export class SeguimientoComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly service = inject(ExpedienteService);
  private readonly session = inject(InsuredSessionService);

  // Se combinan ruta y query: el id solo no identifica un expediente para un asegurado con
  // pólizas en dos compañías, porque se repite entre ellas. `insurerId` viaja desde la lista.
  private readonly state = toSignal(
    combineLatest([this.route.paramMap, this.route.queryParamMap]).pipe(
      map(([params, query]) => ({
        id: params.get('id') ?? '',
        aseguradora: query.get('aseguradora'),
      })),
      switchMap(({ id, aseguradora }) =>
        this.service.getById(id, aseguradora).pipe(
          map((data): LoadState => ({ status: 'ok', data })),
          startWith<LoadState>({ status: 'loading' }),
          catchError((err: HttpErrorResponse) =>
            of<LoadState>({ status: 'error', httpStatus: err.status }),
          ),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as LoadState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly hasError = computed(() => this.state().status === 'error');

  protected readonly data = computed<ExpedienteResponse | null>(() => {
    const s = this.state();
    if (s.status !== 'ok') {
      return null;
    }
    // Cortesía de UX hasta que llegue Auth0: no mostrar expedientes de otro asegurado.
    // El control real de acceso lo va a imponer el backend validando el JWT.
    const sessionId = this.session.insuredId();
    if (sessionId && s.data.insuredId !== sessionId) {
      return null;
    }
    return s.data;
  });

  protected readonly notFound = computed(() => {
    const s = this.state();
    return (s.status === 'error' && s.httpStatus === 404) || (s.status === 'ok' && !this.data());
  });

  protected readonly statusLabel = computed(() => {
    const d = this.data();
    return d ? estadoLabel(d.status) : '';
  });

  // Los `toStatus` del historial: insumo del progreso EFECTIVO (monótono) y del copy
  // reproceso-aware, para que el seguimiento nunca retroceda al día 1 tras subir documentación.
  private readonly pastStatuses = computed<string[]>(
    () => this.data()?.statusHistory?.map((h) => h.toStatus) ?? [],
  );

  protected readonly heroTitle = computed(() => {
    const d = this.data();
    return d ? estadoTituloAseguradoEfectivo(d.status, this.pastStatuses()) : '';
  });

  /** Progreso simplificado (Denunciado → En trámite → Terminado) para el asegurado. */
  protected readonly simplifiedSteps = ['DENUNCIADO', 'EN_TRAMITE', 'TERMINADO'] as const;

  protected readonly simplifiedIndex = computed(() => {
    const d = this.data();
    return d ? this.simplifiedSteps.indexOf(estadoSimplificadoEfectivo(d.status, this.pastStatuses())) : 0;
  });

  protected readonly statusTone = computed<StatusTone>(() => {
    const d = this.data();
    return d ? estadoTone(d.status) : 'neutral';
  });

  // Copy asegurado-safe: sin clasificación/IA ni estados técnicos (ver memoria de
  // visibilidad asegurado vs analista).
  protected readonly statusDescription = computed(() => {
    const d = this.data();
    return d ? estadoDescripcionAseguradoEfectivo(d.status, this.pastStatuses()) : '';
  });

  protected readonly isResolved = computed(() => {
    const d = this.data();
    return d ? isEstadoFinal(d.status) : false;
  });

  protected readonly needsDocs = computed(() => this.data()?.status === 'AWAITING_DOCUMENTATION');

  /**
   * Hilo simplificado para el asegurado: solo los 3 hitos del ciclo visible
   * (Denunciado → En trámite → Terminado) con la fecha en que se alcanzó cada uno.
   * Derivado del historial pero SIN exponer motivos ("clasificación: FAST_TRACK"),
   * estados técnicos ni la decisión interna del analista — eso es vista del analista.
   */
  protected readonly milestones = computed<Milestone[]>(() => {
    const d = this.data();
    if (!d) {
      return [];
    }
    const history = d.statusHistory ?? [];
    const order: EstadoSimplificado[] = ['DENUNCIADO', 'EN_TRAMITE', 'TERMINADO'];
    const currentIndex = order.indexOf(estadoSimplificadoEfectivo(d.status, this.pastStatuses()));

    return order.map((stage, i) => {
      const hit = history.find((h) => estadoSimplificado(h.toStatus) === stage);
      const date = hit?.changedAt ?? (stage === 'DENUNCIADO' ? d.createdAt : null);
      return {
        stage,
        label: SIMPLIFICADO_LABEL[stage],
        date: date ? formatDateTime(date, '') : null,
        reached: i <= currentIndex,
        current: i === currentIndex,
      };
    });
  });

  protected readonly fechaDenuncia = computed(() => formatDateTime(this.data()?.createdAt));

  protected readonly fechaHecho = computed(() => formatDateTime(this.data()?.eventDate));

  protected goToDocumentacion(): void {
    this.router.navigate(['documentacion'], { relativeTo: this.route });
  }
}
