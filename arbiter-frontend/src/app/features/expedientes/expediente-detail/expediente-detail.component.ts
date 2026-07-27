import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';

import { ExpedienteService, AnalystDecisionRequest } from '../expediente.service';
import { ExpedienteResponse, StatusTransition } from '../../../core/models/expediente';
import { clasificacionLabel, clasificacionTone } from '../../../core/models/clasificacion';
import { estadoLabel, estadoSimplificadoLabel, estadoTone } from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { FraudGaugeComponent } from '../../../shared/ui/fraud-gauge/fraud-gauge.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { StatusTimelineComponent } from '../../../shared/ui/status-timeline/status-timeline.component';
import { DocUploadComponent } from '../../../shared/ui/doc-upload/doc-upload.component';
import { CaseDocumentsComponent } from '../case-documents/case-documents.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse }
  | { status: 'error'; httpStatus: number };

type TabId = 'resumen' | 'datos' | 'imagenes' | 'documentacion' | 'conversacion' | 'historial';
type Verb = 'aprobar' | 'rechazar';

/** value=null → la sección muestra "Sin datos" (el backend no provee este campo). */
interface FieldItem { label: string; value: string | null; mono?: boolean; full?: boolean; }

@Component({
  selector: 'app-expediente-detail',
  imports: [
    RouterLink,
    FraudGaugeComponent,
    EmptyStateComponent,
    StatusTimelineComponent,
    DocUploadComponent,
    CaseDocumentsComponent,
    CardComponent,
    ButtonComponent,
    BadgeComponent,
    ModalComponent,
    TextareaComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './expediente-detail.component.html',
  styleUrl: './expediente-detail.component.scss',
})
export class ExpedienteDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(ExpedienteService);

  /** Bumped after a decision is recorded, to refetch the case and reflect the real backend status. */
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
    return s.status === 'ok' ? s.data : null;
  });

  protected readonly notFound = computed(() => {
    const s = this.state();
    return s.status === 'error' && s.httpStatus === 404;
  });

  protected readonly statusLabel = computed(() => {
    const d = this.data();
    return d ? estadoLabel(d.status) : '';
  });

  protected readonly statusTone = computed<StatusTone>(() => {
    const d = this.data();
    return d ? estadoTone(d.status) : 'neutral';
  });

  protected readonly simplifiedStatusLabel = computed(() => {
    const d = this.data();
    return d ? estadoSimplificadoLabel(d.status) : '';
  });

  private static readonly RISK_BAND_GAUGE: Record<string, 1 | 2 | 3 | 4> = {
    LOW: 1,
    MEDIUM: 2,
    HIGH: 3,
    CRITICAL: 4,
  };

  protected readonly riskGaugeBand = computed<1 | 2 | 3 | 4 | null>(() => {
    const band = this.data()?.riskBand;
    return band ? ExpedienteDetailComponent.RISK_BAND_GAUGE[band] : null;
  });

  protected readonly classificationLabel = computed(() => {
    const d = this.data();
    return d ? clasificacionLabel(d.analysisClassification) : '';
  });

  protected readonly classificationTone = computed<StatusTone>(() => {
    const d = this.data();
    return d ? clasificacionTone(d.analysisClassification) : 'neutral';
  });

  protected readonly confidencePercent = computed(() => {
    const d = this.data();
    return d ? Math.round(d.analysisConfidence * 100) : 0;
  });

  /** Grilla del expediente: real donde el backend lo da, null ("Sin datos") en el resto. */
  protected readonly fields = computed<FieldItem[]>(() => {
    const d = this.data();
    return [
      { label: 'N° de siniestro / denuncia', value: d ? `#${d.id}` : null, mono: true },
      { label: 'Canal de origen', value: null },
      { label: 'N° de póliza', value: d?.policyNumber ?? null, mono: true },
      { label: 'N° de certificado', value: null, mono: true },
      { label: 'Rama', value: d?.branch ?? null },
      { label: 'Producto', value: d?.product ?? null },
      { label: 'Asegurado', value: d?.insuredName ?? null },
      { label: 'DNI', value: d?.insuredId ?? null, mono: true },
      { label: 'Tomador', value: null },
      { label: 'Bien asegurado', value: d?.insuredItem ?? null },
      { label: 'Importe reclamado', value: d?.claimedAmount ? `$${d.claimedAmount.toLocaleString()}` : null },
      { label: 'Fecha de denuncia', value: d?.createdAt ? new Date(d.createdAt).toLocaleString('es-AR') : null },
      { label: 'Fecha y hora de ocurrencia', value: d?.eventDate ? new Date(d.eventDate).toLocaleDateString('es-AR') : null },
      { label: 'Causa', value: d?.claimCause ?? null },
      { label: 'Hecho generador', value: null },
      { label: 'Ubicación', value: d?.eventLocation ?? null, full: true },
      { label: 'Descripción', value: d?.description ?? null, full: true },
      { label: 'Analista asignado', value: null },
      { label: 'PEP (declarativo)', value: null },
    ];
  });

  // ----- historial de estados (GET /{id} lo trae con timestamps de cada transición) -----
  protected readonly history = computed<StatusTransition[]>(() => this.data()?.statusHistory ?? []);

  // ----- tabs -----
  protected readonly tabs: { id: TabId; label: string }[] = [
    { id: 'resumen', label: 'Resumen' },
    { id: 'datos', label: 'Datos extraídos' },
    { id: 'imagenes', label: 'Análisis de imágenes' },
    { id: 'documentacion', label: 'Documentación' },
    { id: 'conversacion', label: 'Conversación' },
    { id: 'historial', label: 'Historial' },
  ];
  protected readonly activeTab = signal<TabId>('resumen');
  setTab(t: TabId): void {
    this.activeTab.set(t);
  }

  // ----- clasificación sugerida: aceptar / modificar (local) -----
  protected readonly classifState = signal<'none' | 'aceptada' | 'modificada'>('none');
  acceptClassif(): void {
    this.classifState.set('aceptada');
  }
  modifyClassif(): void {
    this.classifState.set('modificada');
  }

  // ----- decisión del analista (persiste vía POST /cases/{id}/decision) -----
  private readonly verbLabels: Record<Verb, string> = {
    aprobar: 'Aprobar',
    rechazar: 'Rechazar',
  };

  /** Fuente de verdad: el estado del expediente en el backend, no un flag local. */
  protected readonly decisionState = computed<'pending' | 'approved' | 'rejected' | 'not-ready'>(() => {
    switch (this.data()?.status) {
      case 'APPROVED': return 'approved';
      case 'REJECTED': return 'rejected';
      case 'PENDING_ANALYST_REVIEW': return 'pending';
      default: return 'not-ready';
    }
  });

  protected readonly pendingDecision = signal<Verb | null>(null);
  protected readonly showJustify = signal(false);
  protected readonly justification = signal('');
  protected readonly decisionError = signal<string | null>(null);
  protected readonly decisionSaving = signal(false);

  verbLabel(v: Verb | null): string {
    return v ? this.verbLabels[v] : '';
  }

  askDecision(v: Verb): void {
    this.pendingDecision.set(v);
    this.justification.set('');
    this.decisionError.set(null);
    this.showJustify.set(true);
  }
  cancelDecision(): void {
    this.showJustify.set(false);
    this.pendingDecision.set(null);
  }
  confirmDecision(): void {
    const verb = this.pendingDecision();
    if (!this.justification().trim() || !verb) {
      return;
    }

    const d = this.data();
    if (!d) {
      return;
    }

    const decisionPayload: AnalystDecisionRequest = {
      analystId: 'analista-ui',
      decision: verb === 'aprobar' ? 'APPROVE' : 'REJECT',
    };

    this.decisionSaving.set(true);
    this.decisionError.set(null);

    this.service.recordAnalystDecision(d.id, decisionPayload).subscribe({
      next: () => {
        this.showJustify.set(false);
        this.pendingDecision.set(null);
        this.decisionSaving.set(false);
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.decisionSaving.set(false);
        this.decisionError.set(err.error?.detail || 'No se pudo registrar la decisión');
      },
    });
  }

  // ----- carga de documentación adicional (FALTA_DOCUMENTACION) -----
  protected readonly needsDocs = computed(() =>
    this.data()?.analysisClassification === 'FALTA_DOCUMENTACION'
  );

  /** La agenda documental es otra llamada al backend: se refresca con el mismo trigger. */
  protected readonly docsReloadToken = computed(() => this.reloadTrigger());

  onDocsUploaded(): void {
    this.reloadTrigger.update((v) => v + 1);
  }
}
