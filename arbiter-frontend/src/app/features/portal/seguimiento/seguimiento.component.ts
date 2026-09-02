import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';

import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import {
  estadoBadgeLabelAsegurado,
  estadoDescripcionAseguradoEfectivo,
  estadoSimplificadoEfectivo,
  estadoTituloAseguradoEfectivo,
  estadoTone,
  isEstadoFinal,
  movimientoAseguradoLabel,
} from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDateTime } from '../../../core/util/datetime';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CaseDocumentsComponent } from '../../expedientes/case-documents/case-documents.component';
import { CaseChatComponent } from '../../expedientes/case-chat/case-chat.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse }
  | { status: 'error'; httpStatus: number };

/** Un movimiento real del expediente, ya traducido al vocabulario del asegurado. */
interface Movimiento {
  label: string;
  date: string;
  current: boolean;
}

/**
 * Seguimiento de un expediente para el asegurado: hero con estado tranquilizador,
 * acción requerida cuando falta documentación, y timeline de trazabilidad. A
 * diferencia de la vista del analista, acá NO se muestra la recomendación del modelo
 * — el asegurado ve estado y próximos pasos; la clasificación es insumo del analista.
 * La carga de documentación vive en su propia pantalla (portal/cases/:id/documents).
 */
@Component({
  selector: 'app-seguimiento',
  imports: [RouterLink, CardComponent, ButtonComponent, CaseDocumentsComponent, CaseChatComponent, InlineLoadingComponent],
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
        insurer: query.get('insurer'),
      })),
      switchMap(({ id, insurer }) =>
        this.service.getById(id, insurer).pipe(
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
    return d ? estadoBadgeLabelAsegurado(d.status) : '';
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
   * Los movimientos del expediente, en el idioma del asegurado. Los tres hitos de arriba dicen en
   * qué ETAPA está; esto dice QUÉ PASÓ — que era lo que faltaba: "en trámite" durante tres semanas
   * no distingue un expediente que avanza de uno olvidado.
   *
   * Se arma mapeando el ESTADO de cada transición, nunca su {@code reason}: ese campo es interno y
   * trae la clasificación del modelo y el veredicto del peritaje. Las transiciones que no
   * significan nada para el asegurado (una falla técnica de clasificación) no se listan.
   */
  protected readonly movimientos = computed<Movimiento[]>(() => {
    const visibles = (this.data()?.statusHistory ?? [])
      .map((h) => ({ label: movimientoAseguradoLabel(h.toStatus, h.fromStatus), changedAt: h.changedAt }))
      .filter((m): m is { label: string; changedAt: string } => m.label !== null);

    return visibles.map((m, i) => ({
      label: m.label,
      date: formatDateTime(m.changedAt, ''),
      // El último es el estado actual: ahí va el pulso, no en un hito genérico.
      current: i === visibles.length - 1,
    }));
  });

  protected readonly fechaDenuncia = computed(() => formatDateTime(this.data()?.createdAt));

  protected readonly fechaHecho = computed(() => formatDateTime(this.data()?.eventDate));

  protected goToDocuments(): void {
    this.router.navigate(['documents'], { relativeTo: this.route });
  }
}
