import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';

import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { ExpedienteResponse, StatusTransition } from '../../../core/models/expediente';
import { estadoDescripcion, estadoLabel, estadoTone, isEstadoFinal, proximoPaso } from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { DocUploadComponent } from '../../../shared/ui/doc-upload/doc-upload.component';
import { StatusTimelineComponent } from '../../../shared/ui/status-timeline/status-timeline.component';
import { ExpedienteService } from '../../expedientes/expediente.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse }
  | { status: 'error'; httpStatus: number };

/**
 * Seguimiento de un expediente para el asegurado: estado actual, próximos pasos,
 * timeline de transiciones y carga de documentación faltante. A diferencia de la
 * vista del analista, acá NO se muestra la recomendación del modelo — el asegurado
 * ve estado y documentación de su caso; la clasificación es insumo del analista.
 */
@Component({
  selector: 'app-seguimiento',
  imports: [RouterLink, StatusTimelineComponent, DocUploadComponent, CardComponent, BadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './seguimiento.component.html',
  styleUrl: './seguimiento.component.scss',
})
export class SeguimientoComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(ExpedienteService);
  private readonly session = inject(InsuredSessionService);

  /** Bumped tras subir documentación, para refrescar estado + historial. */
  private readonly reloadTrigger = signal(0);

  private readonly state = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
    ]).pipe(
      map(([id]) => id),
      switchMap((id) =>
        this.service.getById(id).pipe(
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

  protected readonly statusTone = computed<StatusTone>(() => {
    const d = this.data();
    return d ? estadoTone(d.status) : 'neutral';
  });

  protected readonly statusDescription = computed(() => {
    const d = this.data();
    return d ? estadoDescripcion(d.status) : '';
  });

  protected readonly nextStep = computed(() => {
    const d = this.data();
    return d && !isEstadoFinal(d.status) ? proximoPaso(d.status) : '';
  });

  protected readonly isResolved = computed(() => {
    const d = this.data();
    return d ? isEstadoFinal(d.status) : false;
  });

  protected readonly needsDocs = computed(() => this.data()?.status === 'AWAITING_DOCUMENTATION');

  protected readonly history = computed<StatusTransition[]>(() => this.data()?.statusHistory ?? []);

  protected readonly fechaDenuncia = computed(() => {
    const d = this.data();
    return d?.createdAt ? new Date(d.createdAt).toLocaleString('es-AR') : '—';
  });

  protected readonly fechaHecho = computed(() => {
    const d = this.data();
    return d?.eventDate ? new Date(d.eventDate).toLocaleString('es-AR') : '—';
  });

  protected onDocsUploaded(): void {
    this.reloadTrigger.update((v) => v + 1);
  }
}
