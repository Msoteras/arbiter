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
import { DocumentAnalysis, ExtractionStatus } from '../../../core/models/expediente';
import { formatDate } from '../../../core/util/datetime';
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

/** `value` null renders as "No aplica": the document doesn't state it. */
interface ExtractedField {
  label: string;
  value: string | null;
  mono?: boolean;
}

type ListState =
  { status: 'loading' } | { status: 'ok'; data: CaseDocument[] } | { status: 'error' };

interface DocRow {
  type: string;
  label: string;
  doc: CaseDocument | null;
  /** Attachment outside the document agenda (e.g. the expert report). */
  extra: boolean;
  /** Set when the model's read of this document broke; flagged in the list so it isn't missed. */
  readIssue: Exclude<ExtractionStatus, 'COMPLETE'> | null;
  /** The vision model noticed signs of tampering; flagged in the list for the same reason. */
  tamperingSigns: boolean;
}

type PreviewState =
  | { status: 'empty' }
  | { status: 'loading'; doc: CaseDocument }
  | { status: 'ok'; doc: CaseDocument; objectUrl: string; safeUrl: SafeResourceUrl }
  | { status: 'unsupported'; doc: CaseDocument; objectUrl: string }
  | { status: 'error'; doc: CaseDocument };

/**
 * Document checklist plus an embedded viewer. Downloads go through HttpClient (the endpoint needs
 * the JWT) into object URLs, which must be revoked manually to avoid leaking memory.
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
  /** See `ExpedienteService.getById`. Null for analyst and supervisor. */
  readonly insurerSlug = input<string | null | undefined>(null);
  /** Bumped by the parent after an upload to refresh the list. */
  readonly reloadToken = input(0);
  /** Empty in the insured portal: the insured never sees the model's readings. */
  readonly extractions = input<DocumentAnalysis[]>([]);
  /**
   * Says so when a document has no reading, even if the case has none at all. Only the analyst's
   * view sets it, once classification is over: `extractions` alone can't tell "no readings" from
   * "not allowed to see them", and the portal must never show the notice.
   */
  readonly showMissingReadings = input(false);
  /** Off in the portal, where the missing-documentation banner already covers it. */
  readonly showMissing = input(true);
  readonly heading = input('Agenda documental');
  /** With both branch and claim cause the checklist uses the configured agenda; otherwise the full catalog. */
  readonly branch = input<string | null>(null);
  readonly claimCause = input<string | null>(null);

  private readonly requiredTypes = toSignal(
    toObservable(computed(() => ({ branch: this.branch(), claimCause: this.claimCause() }))).pipe(
      switchMap(({ branch, claimCause }) =>
        branch && claimCause
          ? this.agenda.slotsForBranch(branch, claimCause)
          : of(CASE_DOCUMENT_TYPES),
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

  /** Only these count towards "N de M". */
  private readonly agendaDocuments = computed<CaseDocument[]>(() => {
    const slots = new Set(this.requiredTypes().map((t) => t.type));
    return this.allDocuments().filter((d) => slots.has(d.type));
  });

  /** Listed outside the checklist; hidden in the portal since the insured didn't send it and may not read it. */
  private readonly extraDocuments = computed<CaseDocument[]>(() => {
    if (!this.showMissing()) return [];
    const slots = new Set(this.requiredTypes().map((t) => t.type));
    return this.allDocuments().filter((d) => !slots.has(d.type));
  });

  private readonly documents = computed<CaseDocument[]>(() => [
    ...this.agendaDocuments(),
    ...this.extraDocuments(),
  ]);

  protected readonly rows = computed<DocRow[]>(() => {
    const docs = this.agendaDocuments();
    const extractions = this.extractions();
    const readIssue = (type: string): DocRow['readIssue'] => {
      const status = extractions.find((e) => e.documentType === type)?.extractionStatus;
      return status === 'PARTIAL' || status === 'FAILED' ? status : null;
    };
    const tamperingSigns = (type: string): boolean =>
      (extractions.find((e) => e.documentType === type)?.visualFindings.length ?? 0) > 0;
    const all = this.requiredTypes().map(({ type, label }) => {
      const doc = docs.find((d) => d.type === type) ?? null;
      return {
        type,
        label,
        doc,
        extra: false,
        readIssue: doc ? readIssue(type) : null,
        tamperingSigns: doc ? tamperingSigns(type) : false,
      };
    });
    const agenda = this.showMissing() ? all : all.filter((r) => r.doc);
    return [
      ...agenda,
      ...this.extraDocuments().map((doc) => ({
        type: doc.type,
        label: documentTypeLabel(doc.type),
        doc,
        extra: true,
        readIssue: readIssue(doc.type),
        tamperingSigns: tamperingSigns(doc.type),
      })),
    ];
  });

  /** -1 when there are none. */
  protected readonly firstExtraIndex = computed(() => this.rows().findIndex((r) => r.extra));

  protected readonly presentCount = computed(() => this.agendaDocuments().length);
  protected readonly totalCount = computed(() => this.requiredTypes().length);

  protected readonly preview = signal<PreviewState>({ status: 'empty' });

  protected readonly selectedId = computed(() => {
    const p = this.preview();
    return p.status === 'empty' ? null : p.doc.id;
  });

  protected readonly selectedExtraction = computed<DocumentAnalysis | null>(() => {
    const p = this.preview();
    if (p.status === 'empty') return null;
    return this.extractions().find((e) => e.documentType === p.doc.type) ?? null;
  });

  /**
   * No reading for this document: not read on that path (Fast Track, an exclusion), uploaded later,
   * or its reading broke and was removed. The cause isn't known here, so the notice doesn't guess.
   */
  protected readonly selectedNotAnalyzed = computed(
    () =>
      (this.showMissingReadings() || this.extractions().length > 0) &&
      this.selectedExtraction() === null &&
      this.preview().status !== 'empty',
  );

  /** The field grid is for paper documents; for a photo it would be all "No aplica". */
  protected readonly selectedIsImage = computed(() => {
    const p = this.preview();
    return p.status !== 'empty' && this.isImage(p.doc.contentType);
  });

  constructor() {
    // Opens the first document, also when the selected one disappeared after a re-upload.
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
          // Some browsers leave the blob type empty; re-type it so <img>/<iframe> render it.
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

  /** A field the document doesn't carry is `null` ("No aplica"), never a mismatch. */
  protected extractedFields(doc: DocumentAnalysis): ExtractedField[] {
    return [
      // DATE column, no time part.
      {
        label: 'Fecha del documento',
        value: doc.documentDate ? formatDate(doc.documentDate) : null,
      },
      { label: 'Importe', value: doc.amount == null ? null : `$${doc.amount.toLocaleString()}` },
      { label: 'Bien que nombra', value: doc.itemDescription },
      { label: 'Marca', value: doc.brand },
      { label: 'Modelo', value: doc.model },
      { label: 'IMEI', value: doc.imei, mono: true },
      { label: 'Damnificado', value: this.affectedPartyLabel(doc.affectedParty) },
      // Untyped extras exist only when the document carries them, so they never render "No aplica".
      ...(doc.details ?? []).map((detail) => ({ label: detail.name, value: detail.value })),
    ];
  }

  /** `DESCONOCIDO` is a value, not missing data: the family-group rule simply doesn't apply. */
  private affectedPartyLabel(affectedParty: string): string {
    const labels: Record<string, string> = {
      TITULAR: 'El titular de la póliza',
      FAMILIAR: 'Un familiar',
      TERCERO: 'Un tercero',
      DESCONOCIDO: 'No lo aclara el documento',
    };
    return labels[affectedParty] ?? affectedParty;
  }

  protected uploadedAt(iso: string): string {
    return new Date(iso).toLocaleDateString('es-AR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
    });
  }
}
