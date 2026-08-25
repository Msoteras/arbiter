import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';

import { ExpedienteService } from '../expediente.service';
import { DocumentAgendaService } from '../document-agenda.service';
import {
  CASE_DOCUMENT_TYPES,
  CaseDocument,
  CaseDocumentType,
  documentFormatLabel,
  documentTypeLabel,
  formatFileSize,
  isPreviewableImage,
  isPreviewablePdf,
} from '../../../core/models/case-document';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';

type ListState =
  | { status: 'loading' }
  | { status: 'ok'; data: CaseDocument[] }
  | { status: 'error' };

/** Una fila por tipo canónico: presente (con su documento) o faltante. */
interface DocRow {
  type: string;
  label: string;
  doc: CaseDocument | null;
  /** Adjunto que no ocupa un casillero de la agenda (hoy, el informe pericial). */
  extra: boolean;
}

type PreviewState =
  | { status: 'empty' }
  | { status: 'loading'; doc: CaseDocument }
  | { status: 'ok'; doc: CaseDocument; objectUrl: string; safeUrl: SafeResourceUrl }
  | { status: 'unsupported'; doc: CaseDocument; objectUrl: string }
  | { status: 'error'; doc: CaseDocument };

/**
 * Agenda documental del expediente: checklist de los 4 tipos requeridos + visor embebido.
 *
 * El visor va acá adentro y no en un modal ni en una pestaña aparte del browser porque
 * el analista necesita leer la denuncia policial mientras mira el resto del expediente.
 *
 * Tanto la lista como la descarga pasan por HttpClient (ver ExpedienteService): el
 * endpoint exige el JWT y un href plano saldría sin el header. El contenido se vuelca a
 * un object URL, que hay que revocar a mano para no filtrar memoria al cambiar de doc.
 */
@Component({
  selector: 'app-case-documents',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CardComponent, BadgeComponent, InlineLoadingComponent],
  templateUrl: './case-documents.component.html',
  styleUrl: './case-documents.component.scss',
})
export class CaseDocumentsComponent {
  private readonly service = inject(ExpedienteService);
  private readonly agenda = inject(DocumentAgendaService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly destroyRef = inject(DestroyRef);

  readonly caseId = input.required<number>();
  /**
   * De qué aseguradora es el expediente — mismo motivo que en `ExpedienteService.getById`: un
   * asegurado con pólizas en más de una compañía puede tener acá un expediente que no vive en el
   * tenant por defecto de su sesión. Null para el analista/referente (single-tenant, no ambiguo).
   *
   * <p>Acepta `undefined` además de `null` porque en `ExpedienteResponse` el campo es opcional
   * (solo viene poblado en las vistas que mezclan compañías), y quien lo bindea lo pasa derecho.
   */
  readonly insurerSlug = input<string | null | undefined>(null);
  /** Se bumpea desde el detalle al subir documentación, para refrescar la lista. */
  readonly reloadToken = input(0);
  /**
   * Mostrar también los tipos que faltan (checklist completo). El analista necesita el
   * hueco tanto como lo cargado; en el portal se apaga, porque al asegurado ya le avisa
   * el banner de "falta documentación" y ahí la pregunta es "¿qué mandé?".
   */
  readonly showMissing = input(true);
  readonly heading = input('Agenda documental');
  /**
   * Ramo y hecho generador del expediente. Con esto el checklist se arma contra la agenda REAL que
   * configuró el referente para esa combinación, no contra el catálogo completo. Sin ambos (o sin
   * agenda configurada) cae al catálogo completo.
   */
  readonly branch = input<string | null>(null);
  readonly claimCause = input<string | null>(null);

  /** Tipos de documento requeridos del ramo + hecho generador (o el catálogo completo como fallback). */
  private readonly requiredTypes = toSignal(
    toObservable(computed(() => ({ branch: this.branch(), claimCause: this.claimCause() }))).pipe(
      switchMap(({ branch, claimCause }) =>
        branch && claimCause ? this.agenda.slotsForBranch(branch, claimCause) : of(CASE_DOCUMENT_TYPES),
      ),
    ),
    { initialValue: CASE_DOCUMENT_TYPES as readonly CaseDocumentType[] },
  );

  private readonly state = toSignal(
    combineLatest([
      toObservable(this.caseId),
      toObservable(this.reloadToken),
      toObservable(this.insurerSlug),
    ]).pipe(
      switchMap(([id, , slug]) =>
        this.service.listDocuments(id, slug).pipe(
          map((data): ListState => ({ status: 'ok', data })),
          startWith<ListState>({ status: 'loading' }),
          catchError((_err: HttpErrorResponse) => of<ListState>({ status: 'error' })),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as ListState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly hasError = computed(() => this.state().status === 'error');

  private readonly allDocuments = computed<CaseDocument[]>(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : [];
  });

  /** Los que ocupan un casillero de la agenda: son los que cuentan para "N de M". */
  private readonly agendaDocuments = computed<CaseDocument[]>(() => {
    const slots = new Set(this.requiredTypes().map((t) => t.type));
    return this.allDocuments().filter((d) => slots.has(d.type));
  });

  /**
   * Lo adjunto al expediente que no sale de la agenda — hoy, el informe que el analista carga
   * cuando vuelve el perito. Se lista igual (si no, queda un documento que el visor abre pero la
   * lista no ofrece), pero fuera del checklist: no es un casillero que el asegurado deba llenar.
   *
   * <p>En el portal no se muestra: esa sección es "la documentación que enviaste", y el informe
   * pericial no lo mandó el asegurado ni es suyo para leer.
   */
  private readonly extraDocuments = computed<CaseDocument[]>(() => {
    if (!this.showMissing()) return [];
    const slots = new Set(this.requiredTypes().map((t) => t.type));
    return this.allDocuments().filter((d) => !slots.has(d.type));
  });

  /** Lo visible en esta vista, en el orden de la lista. Es lo que el visor puede abrir. */
  private readonly documents = computed<CaseDocument[]>(() => [
    ...this.agendaDocuments(),
    ...this.extraDocuments(),
  ]);

  /** Las filas del checklist; con showMissing=false quedan solo las cargadas. */
  protected readonly rows = computed<DocRow[]>(() => {
    const docs = this.agendaDocuments();
    const all = this.requiredTypes().map(({ type, label }) => ({
      type,
      label,
      doc: docs.find((d) => d.type === type) ?? null,
      extra: false,
    }));
    const agenda = this.showMissing() ? all : all.filter((r) => r.doc);
    return [
      ...agenda,
      ...this.extraDocuments().map((doc) => ({
        type: doc.type,
        label: documentTypeLabel(doc.type),
        doc,
        extra: true,
      })),
    ];
  });

  /** Índice de la primera fila fuera de agenda, para separarlas con su encabezado. -1 si no hay. */
  protected readonly firstExtraIndex = computed(() => this.rows().findIndex((r) => r.extra));

  protected readonly presentCount = computed(() => this.agendaDocuments().length);
  protected readonly totalCount = computed(() => this.requiredTypes().length);

  protected readonly preview = signal<PreviewState>({ status: 'empty' });

  protected readonly selectedId = computed(() => {
    const p = this.preview();
    return p.status === 'empty' ? null : p.doc.id;
  });

  constructor() {
    // Al cargar (o refrescarse) la lista, abre el primer documento disponible: la pestaña
    // arranca mostrando algo en vez de un panel vacío. Si el seleccionado desapareció
    // (se reemplazó al resubir), vuelve a caer en el primero.
    effect(() => {
      const docs = this.documents();
      const current = untracked(() => this.preview());
      if (docs.length === 0) {
        if (current.status !== 'empty') this.clearPreview();
        return;
      }
      const stillThere = current.status !== 'empty' && docs.some((d) => d.id === current.doc.id);
      if (!stillThere) this.select(docs[0]);
    });

    this.destroyRef.onDestroy(() => this.revokeCurrent());
  }

  protected select(doc: CaseDocument): void {
    if (this.selectedId() === doc.id) return;
    this.revokeCurrent();
    this.preview.set({ status: 'loading', doc });

    this.service
      .downloadDocument(this.caseId(), doc.id, this.insurerSlug())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          // El content-type viene del backend; el del blob puede llegar vacío según el
          // navegador, así que se re-tipa para que <img>/<iframe> lo interpreten bien.
          const typed = blob.type ? blob : new Blob([blob], { type: doc.contentType });
          const objectUrl = URL.createObjectURL(typed);
          if (isPreviewableImage(doc.contentType) || isPreviewablePdf(doc.contentType)) {
            this.preview.set({
              status: 'ok',
              doc,
              objectUrl,
              safeUrl: this.sanitizer.bypassSecurityTrustResourceUrl(objectUrl),
            });
          } else {
            this.preview.set({ status: 'unsupported', doc, objectUrl });
          }
        },
        error: () => this.preview.set({ status: 'error', doc }),
      });
  }

  private revokeCurrent(): void {
    const p = this.preview();
    if (p.status === 'ok' || p.status === 'unsupported') {
      URL.revokeObjectURL(p.objectUrl);
    }
  }

  private clearPreview(): void {
    this.revokeCurrent();
    this.preview.set({ status: 'empty' });
  }

  protected isImage(contentType: string): boolean {
    return isPreviewableImage(contentType);
  }

  protected isPdf(contentType: string): boolean {
    return isPreviewablePdf(contentType);
  }

  protected formatLabel(contentType: string): string {
    return documentFormatLabel(contentType);
  }

  protected fileSize(bytes: number): string {
    return formatFileSize(bytes);
  }

  protected uploadedAt(iso: string): string {
    return new Date(iso).toLocaleDateString('es-AR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
    });
  }
}
