import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { ExpedienteService } from '../expediente.service';
import { ExpedienteResponse } from '../../../core/models/expediente';
import { clasificacionLabel } from '../../../core/models/clasificacion';
import { FraudGaugeComponent } from '../../../shared/ui/fraud-gauge/fraud-gauge.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';

interface DocUploadSlot {
  type: string;
  label: string;
  file: File | null;
}

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse }
  | { status: 'error'; httpStatus: number };

type TabId = 'resumen' | 'datos' | 'imagenes' | 'documentacion' | 'conversacion' | 'historial';
type Verb = 'aprobar' | 'rechazar' | 'derivar';

/** value=null → la sección muestra "Sin datos" (el backend no provee este campo). */
interface FieldItem { label: string; value: string | null; mono?: boolean; full?: boolean; }

@Component({
  selector: 'app-expediente-detail',
  imports: [RouterLink, FraudGaugeComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './expediente-detail.component.html',
  styleUrl: './expediente-detail.component.scss',
})
export class ExpedienteDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(ExpedienteService);

  private readonly state = toSignal(
    this.route.paramMap.pipe(
      map((params) => params.get('id') ?? ''),
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

  protected readonly classificationLabel = computed(() => {
    const d = this.data();
    return d ? clasificacionLabel(d.analysisClassification) : '';
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
      { label: 'Asegurado', value: d?.insuredId ?? null, mono: true },
      { label: 'Tomador', value: null },
      { label: 'Bien asegurado', value: d?.insuredItem ?? null },
      { label: 'Importe reclamado', value: d?.claimedAmount ? `$${d.claimedAmount.toLocaleString()}` : null },
      { label: 'Fecha de denuncia', value: null },
      { label: 'Fecha y hora de ocurrencia', value: d?.eventDate ? new Date(d.eventDate).toLocaleDateString('es-AR') : null },
      { label: 'Causa', value: d?.claimCause ?? null },
      { label: 'Hecho generador', value: null },
      { label: 'Ubicación', value: d?.eventLocation ?? null, full: true },
      { label: 'Descripción', value: d?.description ?? null, full: true },
      { label: 'Analista asignado', value: null },
      { label: 'PEP (declarativo)', value: null },
    ];
  });

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

  // ----- decisión del analista (local; no persiste: falta POST /decision) -----
  private readonly verbLabels: Record<Verb, string> = {
    aprobar: 'Aprobar',
    rechazar: 'Rechazar',
    derivar: 'Derivar',
  };
  protected readonly decision = signal<Verb | null>(null);
  protected readonly pendingDecision = signal<Verb | null>(null);
  protected readonly showJustify = signal(false);
  protected readonly justification = signal('');
  protected readonly decisionLabel = computed(() => this.verbLabel(this.decision()));

  verbLabel(v: Verb | null): string {
    return v ? this.verbLabels[v] : '';
  }

  askDecision(v: Verb): void {
    this.pendingDecision.set(v);
    this.justification.set('');
    this.showJustify.set(true);
  }
  cancelDecision(): void {
    this.showJustify.set(false);
    this.pendingDecision.set(null);
  }
  confirmDecision(): void {
    if (!this.justification().trim()) {
      return;
    }
    this.decision.set(this.pendingDecision());
    this.showJustify.set(false);
  }
  onJustifyInput(e: Event): void {
    this.justification.set((e.target as HTMLTextAreaElement).value);
  }

  // ----- carga de documentación adicional (FALTA_DOCUMENTACION) -----
  protected readonly needsDocs = computed(() =>
    this.data()?.analysisClassification === 'FALTA_DOCUMENTACION'
  );

  protected readonly docUploadSlots = signal<DocUploadSlot[]>([
    { type: 'police_report', label: 'Denuncia policial', file: null },
    { type: 'item_photo', label: 'Foto del bien', file: null },
    { type: 'invoice', label: 'Factura de compra', file: null },
    { type: 'quote', label: 'Presupuesto de reparación', file: null },
  ]);

  protected readonly uploadingDocs = signal(false);
  protected readonly uploadError = signal<string | null>(null);
  protected readonly uploadedCount = computed(() => this.docUploadSlots().filter(s => s.file).length);

  onDocFileChange(index: number, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.docUploadSlots.update(slots => {
      const updated = [...slots];
      updated[index] = { ...updated[index], file };
      return updated;
    });
  }

  removeDocFile(index: number): void {
    this.docUploadSlots.update(slots => {
      const updated = [...slots];
      updated[index] = { ...updated[index], file: null };
      return updated;
    });
  }

  submitDocs(): void {
    const d = this.data();
    if (!d || this.uploadingDocs()) return;

    const docs = new Map<string, File>();
    for (const slot of this.docUploadSlots()) {
      if (slot.file) docs.set(slot.type, slot.file);
    }
    if (docs.size === 0) return;

    this.uploadingDocs.set(true);
    this.uploadError.set(null);

    this.service.uploadDocuments(d.id, docs).subscribe({
      next: () => {
        this.uploadingDocs.set(false);
        window.location.reload();
      },
      error: (err) => {
        this.uploadingDocs.set(false);
        this.uploadError.set(err.error?.message || err.message || 'Error al subir documentos');
      },
    });
  }
}
