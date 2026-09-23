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

interface Movimiento {
  label: string;
  date: string;
  current: boolean;
}

/** Insured-facing case view: never shows the model's classification or recommendation. */
@Component({
  selector: 'app-seguimiento',
  imports: [
    RouterLink,
    CardComponent,
    ButtonComponent,
    CaseDocumentsComponent,
    CaseChatComponent,
    InlineLoadingComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './seguimiento.component.html',
  styleUrl: './seguimiento.component.scss',
})
export class SeguimientoComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly service = inject(ExpedienteService);
  private readonly session = inject(InsuredSessionService);

  // Case ids repeat across insurers, so the `insurer` query param is needed to resolve the tenant.
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
    // UX courtesy only: the backend enforces access.
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

  // Feeds the "effective" progress, so it never goes back to step 1 after re-uploading documents.
  private readonly pastStatuses = computed<string[]>(
    () => this.data()?.statusHistory?.map((h) => h.toStatus) ?? [],
  );

  protected readonly heroTitle = computed(() => {
    const d = this.data();
    return d ? estadoTituloAseguradoEfectivo(d.status, this.pastStatuses()) : '';
  });

  protected readonly simplifiedSteps = ['DENUNCIADO', 'EN_TRAMITE', 'TERMINADO'] as const;

  protected readonly simplifiedIndex = computed(() => {
    const d = this.data();
    return d
      ? this.simplifiedSteps.indexOf(estadoSimplificadoEfectivo(d.status, this.pastStatuses()))
      : 0;
  });

  protected readonly statusTone = computed<StatusTone>(() => {
    const d = this.data();
    return d ? estadoTone(d.status) : 'neutral';
  });

  protected readonly statusDescription = computed(() => {
    const d = this.data();
    return d ? estadoDescripcionAseguradoEfectivo(d.status, this.pastStatuses()) : '';
  });

  protected readonly isResolved = computed(() => {
    const d = this.data();
    return d ? isEstadoFinal(d.status) : false;
  });

  protected readonly needsDocs = computed(() => this.data()?.status === 'AWAITING_DOCUMENTATION');

  // Unlike the expert assessment, the repair provider is disclosed to the insured. The status
  // check avoids showing a stale value once the case is back from repair.
  protected readonly servicioTecnico = computed(() => {
    const d = this.data();
    return d?.status === 'PENDING_REPAIR' ? d.repairProvider : null;
  });

  /**
   * Built from each transition's status, never its `reason`: that field is internal and carries
   * the model's classification and the expert's verdict.
   */
  protected readonly movimientos = computed<Movimiento[]>(() => {
    const visibles = (this.data()?.statusHistory ?? [])
      .map((h) => ({
        label: movimientoAseguradoLabel(h.toStatus, h.fromStatus),
        changedAt: h.changedAt,
      }))
      .filter((m): m is { label: string; changedAt: string } => m.label !== null)
      // Collapse consecutive entries with the same label (e.g. classification retries), keeping
      // the last one: it marks when the case entered its current stage.
      .filter((m, i, todos) => i === todos.length - 1 || todos[i + 1].label !== m.label);

    return visibles.map((m, i) => ({
      label: m.label,
      date: formatDateTime(m.changedAt, ''),
      current: i === visibles.length - 1,
    }));
  });

  protected readonly fechaDenuncia = computed(() => formatDateTime(this.data()?.createdAt));

  protected readonly fechaHecho = computed(() => formatDateTime(this.data()?.eventDate));

  protected goToDocuments(): void {
    // Forward `insurer`, or the documents screen resolves the case against the default tenant.
    const insurer = this.route.snapshot.queryParamMap.get('insurer');
    this.router.navigate(['documents'], {
      relativeTo: this.route,
      queryParams: insurer ? { insurer } : {},
    });
  }
}
