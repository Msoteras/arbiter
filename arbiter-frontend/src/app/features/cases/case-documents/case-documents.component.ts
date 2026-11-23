import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';

import { CaseService } from '../case.service';
import { DocumentAgendaService } from '../document-agenda.service';
import { DocumentTypeService } from '../../../core/services/document-type.service';
import {
  CorrectableField,
  DocumentAnalysis,
  DocumentCorrection,
  ExtractionStatus,
} from '../../../core/models/case';
import { formatDate, formatDateTime } from '../../../core/util/datetime';
import { amountInputDisplay, amountInputLabel, amountInputValue } from '../../../core/util/money';
import {
  CaseDocument,
  CaseDocumentType,
  documentFormatLabel,
  formatFileSize,
  isPreviewableImage,
  isPreviewablePdf,
} from '../../../core/models/case-document';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { DatePickerComponent } from '../../../shared/ui/date-picker/date-picker.component';
import { IconButtonComponent } from '../../../shared/ui/icon-button/icon-button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { SkeletonComponent } from '../../../shared/ui/skeleton/skeleton.component';

/** `value` null renders as "No aplica": the document doesn't state it. */
interface ExtractedField {
  label: string;
  value: string | null;
  mono?: boolean;
  target?: CorrectionTarget;
  correction?: { read: string | null; by: string; at: string };
}

type CorrectionKind = 'date' | 'amount' | 'text';

type TypedField = 'DOCUMENT_DATE' | 'AMOUNT' | 'ITEM_DESCRIPTION';

interface CorrectionTarget {
  field: CorrectableField;
  name: string | null;
  kind: CorrectionKind;
}

interface CorrectionLine {
  label: string;
  previous: string;
  current: string;
  by: string;
  at: string;
}

const TYPED_FIELDS: Record<TypedField, { label: string; kind: CorrectionKind }> = {
  DOCUMENT_DATE: { label: 'Fecha del documento', kind: 'date' },
  AMOUNT: { label: 'Importe', kind: 'amount' },
  ITEM_DESCRIPTION: { label: 'Bien que nombra', kind: 'text' },
};

const isTyped = (field: CorrectableField): field is TypedField => field in TYPED_FIELDS;

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

/** A request to open one document. A new object per request, so asking again still opens it. */
export interface DocumentFocus {
  type: string;
}

/**
 * Document checklist plus an embedded viewer. Downloads go through HttpClient (the endpoint needs
 * the JWT) into object URLs, which must be revoked manually to avoid leaking memory.
 */
@Component({
  selector: 'app-case-documents',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CardComponent,
    BadgeComponent,
    ButtonComponent,
    DatePickerComponent,
    IconButtonComponent,
    InputComponent,
    InlineLoadingComponent,
    ModalComponent,
    SkeletonComponent,
  ],
  templateUrl: './case-documents.component.html',
  styleUrl: './case-documents.component.scss',
})
export class CaseDocumentsComponent {
  private readonly service = inject(CaseService);
  private readonly agenda = inject(DocumentAgendaService);
  private readonly documentTypes = inject(DocumentTypeService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly destroyRef = inject(DestroyRef);

  readonly caseId = input.required<number>();
  /** See `CaseService.getById`. Null for analyst and supervisor. */
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
  /** Document to open instead of the first one (a link from the model's reasons). */
  readonly focus = input<DocumentFocus | null>(null);
  readonly canCorrect = input(false);
  readonly corrected = output<void>();

  protected readonly editing = signal<CorrectionTarget | null>(null);
  protected readonly draft = signal('');
  protected readonly correctionSaving = signal(false);
  protected readonly correctionError = signal<string | null>(null);
  private readonly savedCorrections = linkedSignal<
    DocumentAnalysis[],
    { documentId: number; correction: DocumentCorrection }[]
  >({
    source: () => this.extractions(),
    computation: () => [],
  });
  protected readonly amountInputDisplay = amountInputDisplay;
  protected readonly amountInputLabel = amountInputLabel;
  protected readonly amountInputValue = amountInputValue;

  private readonly requiredTypes = toSignal(
    toObservable(computed(() => ({ branch: this.branch(), claimCause: this.claimCause() }))).pipe(
      switchMap(({ branch, claimCause }) => this.agenda.slotsFor(branch, claimCause)),
    ),
    { initialValue: [] as readonly CaseDocumentType[] },
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
          catchError(() => of<ListState>({ status: 'error' })),
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
        label: doc.description ?? this.documentTypes.label(doc.type),
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
    const extraction = this.extractions().find((e) => e.documentType === p.doc.type);
    if (!extraction) return null;
    const saved = this.savedCorrections()
      .filter((s) => s.documentId === extraction.documentId)
      .map((s) => s.correction);
    return saved.length === 0
      ? extraction
      : { ...extraction, corrections: [...extraction.corrections, ...saved] };
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
    // Opens the focused document or the first one, also when the selected one disappeared after a
    // re-upload.
    effect(() => {
      const docs = this.documents();
      const focused = docs.find((d) => d.type === this.focus()?.type);
      const current = untracked(() => this.preview());
      if (docs.length === 0) {
        if (current.status !== 'empty') this.clearPreview();
        return;
      }
      const stillThere = current.status !== 'empty' && docs.some((d) => d.id === current.doc.id);
      if (focused) untracked(() => this.open(focused));
      else if (!stillThere) this.select(docs[0]);
    });

    this.destroyRef.onDestroy(() => this.revokeCurrent());
  }

  /** Starts collapsed; opening a document, from the list or from a link, opens it. */
  protected readonly previewCollapsed = signal(true);
  protected readonly maximized = signal(false);

  /** What the analyst asked for: the document, with the preview open to show it. */
  protected open(doc: CaseDocument): void {
    this.select(doc);
    this.previewCollapsed.set(false);
  }

  protected select(doc: CaseDocument): void {
    if (this.selectedId() === doc.id) return;
    this.cancelCorrection();
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
    const typed = (field: TypedField, read: string | null): ExtractedField =>
      this.fieldWithCorrection(
        doc,
        { field, name: null, kind: TYPED_FIELDS[field].kind },
        TYPED_FIELDS[field].label,
        read,
      );
    return [
      typed('DOCUMENT_DATE', doc.documentDate),
      typed('AMOUNT', doc.amount == null ? null : String(doc.amount)),
      typed('ITEM_DESCRIPTION', doc.itemDescription),
      // Named by the policy, so they vary with the line of insurance. All-digit ones are identifiers.
      ...this.namedFields(doc, 'ITEM_ATTRIBUTE', doc.itemAttributes ?? []).map((attribute) => {
        const shown = this.fieldWithCorrection(
          doc,
          { field: 'ITEM_ATTRIBUTE', name: attribute.name, kind: 'text' },
          attribute.name.charAt(0).toUpperCase() + attribute.name.slice(1),
          attribute.value,
        );
        return { ...shown, mono: /^\d+$/.test(shown.value ?? '') };
      }),
      { label: 'Damnificado', value: this.affectedPartyLabel(doc.affectedParty) },
      // Untyped extras exist only when the document carries them, so they never render "No aplica".
      ...this.namedFields(doc, 'DETAIL', doc.details ?? []).map((detail) =>
        this.fieldWithCorrection(
          doc,
          { field: 'DETAIL', name: detail.name, kind: 'text' },
          detail.name,
          detail.value,
        ),
      ),
    ];
  }

  private namedFields(
    doc: DocumentAnalysis,
    field: 'ITEM_ATTRIBUTE' | 'DETAIL',
    read: { name: string; value: string }[],
  ): { name: string; value: string | null }[] {
    const correctedOnly = doc.corrections
      .filter((c) => c.field === field && c.fieldName && !read.some((r) => r.name === c.fieldName))
      .map((c) => c.fieldName as string);
    return [...read, ...[...new Set(correctedOnly)].map((name) => ({ name, value: null }))];
  }

  private fieldWithCorrection(
    doc: DocumentAnalysis,
    target: CorrectionTarget,
    label: string,
    read: string | null,
  ): ExtractedField {
    const latest = this.latestCorrection(doc, target);
    return {
      label,
      value: this.display(target.kind, latest ? latest.newValue : read),
      target,
      correction: latest
        ? {
            read: this.display(target.kind, read),
            by: latest.analystName,
            at: formatDateTime(latest.correctedAt),
          }
        : undefined,
    };
  }

  private latestCorrection(
    doc: DocumentAnalysis,
    target: CorrectionTarget,
  ): DocumentCorrection | null {
    return (
      [...doc.corrections]
        .reverse()
        .find((c) => c.field === target.field && c.fieldName === target.name) ?? null
    );
  }

  private readValue(doc: DocumentAnalysis, target: CorrectionTarget): string | null {
    switch (target.field) {
      case 'DOCUMENT_DATE':
        return doc.documentDate;
      case 'AMOUNT':
        return doc.amount == null ? null : String(doc.amount);
      case 'ITEM_DESCRIPTION':
        return doc.itemDescription;
      case 'ITEM_ATTRIBUTE':
        return doc.itemAttributes.find((pair) => pair.name === target.name)?.value ?? null;
      case 'DETAIL':
        return doc.details.find((pair) => pair.name === target.name)?.value ?? null;
    }
  }

  private display(kind: CorrectionKind, value: string | null): string | null {
    if (value == null) return null;
    if (kind === 'date') return formatDate(value);
    if (kind === 'amount') return `$${Number(value).toLocaleString()}`;
    return value;
  }

  protected correctionLines(doc: DocumentAnalysis): CorrectionLine[] {
    return [...doc.corrections].reverse().map((c) => {
      const kind = isTyped(c.field) ? TYPED_FIELDS[c.field].kind : 'text';
      return {
        label: isTyped(c.field) ? TYPED_FIELDS[c.field].label : (c.fieldName ?? ''),
        previous: this.display(kind, c.previousValue) ?? 'Sin dato',
        current: this.display(kind, c.newValue) ?? '',
        by: c.analystName,
        at: formatDateTime(c.correctedAt),
      };
    });
  }

  protected isEditing(target: CorrectionTarget | undefined): boolean {
    const editing = this.editing();
    return !!target && !!editing && editing.field === target.field && editing.name === target.name;
  }

  protected startCorrection(doc: DocumentAnalysis, target: CorrectionTarget): void {
    this.draft.set(
      this.latestCorrection(doc, target)?.newValue ?? this.readValue(doc, target) ?? '',
    );
    this.correctionError.set(null);
    this.editing.set(target);
  }

  protected cancelCorrection(): void {
    this.editing.set(null);
    this.correctionError.set(null);
  }

  protected saveCorrection(doc: DocumentAnalysis): void {
    const target = this.editing();
    const value = this.draft().trim();
    if (!target || !value || this.correctionSaving()) return;
    this.correctionSaving.set(true);
    this.correctionError.set(null);
    this.service
      .correctDocument(this.caseId(), doc.documentId, target.field, target.name, value)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (correction) => {
          this.correctionSaving.set(false);
          this.savedCorrections.update((saved) => [
            ...saved,
            { documentId: doc.documentId, correction },
          ]);
          this.editing.set(null);
          this.corrected.emit();
        },
        error: (err: HttpErrorResponse) => {
          this.correctionSaving.set(false);
          this.correctionError.set(err.error?.detail || 'No se pudo guardar la corrección.');
        },
      });
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
