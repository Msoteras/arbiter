import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { ExpedienteService } from '../../../features/expedientes/expediente.service';
import { DocumentAgendaService } from '../../../features/expedientes/document-agenda.service';
import { CASE_DOCUMENT_TYPES, CaseDocumentType } from '../../../core/models/case-document';
import { ButtonComponent } from '../button/button.component';
import { FilePreviewComponent } from '../file-preview/file-preview.component';
import { InlineLoadingComponent } from '../inline-loading/inline-loading.component';

interface DocUploadSlot {
  type: string;
  label: string;
  file: File | null;
}

/**
 * Neither source has a sensible placeholder while in flight: showing the full catalog would ask
 * again for documents already uploaded, so no rows are rendered until both resolve.
 */
type FetchState<T> = { status: 'loading' } | { status: 'ok'; value: T };

/** Uploading documents via POST /cases/{id}/documents re-triggers classification on the backend. */
@Component({
  selector: 'app-doc-upload',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonComponent, FilePreviewComponent, InlineLoadingComponent],
  template: `
    @if (loadingSlots()) {
      <app-inline-loading message="Revisando qué documentación falta…" />
    } @else {
      <p class="muted">
        La evaluación indica que faltan documentos requeridos. Subí la documentación faltante para
        que el caso se vuelva a evaluar.
      </p>
      <p class="hint">JPG, PNG o PDF · hasta 10 MB por archivo</p>

      @for (slot of slots(); track slot.type; let i = $index) {
        <div
          class="doc-row"
          [class.dragover]="dragOverIndex() === i"
          (dragover)="onDragOver($event, i)"
          (dragleave)="onDragLeave()"
          (drop)="onDrop($event, i)"
        >
          <span class="doc-row-label">{{ slot.label }}</span>
          @if (slot.file) {
            <div class="doc-row-file">
              <app-file-preview [file]="slot.file" />
              <button type="button" class="doc-row-remove" (click)="removeFile(i)">✕</button>
            </div>
          } @else {
            <label class="doc-row-upload">
              Elegir o arrastrá el archivo
              <input type="file" accept="image/*,.pdf" (change)="onFileChange(i, $event)" hidden />
            </label>
          }
        </div>
      }

      @if (error()) {
        <p class="upload-error">{{ error() }}</p>
      }

      <app-button
        class="submit-btn"
        [disabled]="selectedCount() === 0 || uploading()"
        (click)="submit()"
      >
        {{ uploading() ? 'Enviando…' : 'Enviar documentación' }}
      </app-button>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .muted {
      margin: 0 0 var(--space-2);
      color: var(--text-muted);
      font-size: var(--font-size-body);
    }
    .hint {
      margin: 0 0 var(--space-3);
      color: var(--text-muted);
      font-size: var(--font-size-sm);
    }
    .doc-row {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--space-3);
      padding: var(--space-2) 0;
      border-bottom: 1px solid var(--border-subtle);
      border-radius: var(--radius-ctl);
      transition: background-color 0.1s;
    }
    .doc-row.dragover {
      background: var(--surface-sunken);
    }
    .doc-row-label {
      font-size: var(--font-size-body);
      color: var(--text-secondary);
    }
    /* Takes the free width: the expanded preview needs the whole row. */
    .doc-row-file {
      display: flex;
      align-items: flex-start;
      gap: var(--space-2);
      flex: 1;
      min-width: 0;
    }
    .doc-row-file app-file-preview {
      flex: 1;
      min-width: 0;
    }
    .doc-row-remove {
      border: none;
      background: none;
      cursor: pointer;
      color: var(--text-muted);
      font-size: var(--font-size-sm);
      padding: 2px 6px;
    }
    .doc-row-remove:hover {
      color: var(--text-primary);
    }
    .doc-row-upload {
      font-size: var(--font-size-sm);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      padding: var(--space-1) var(--space-3);
      cursor: pointer;
      color: var(--text-tertiary);
      background: var(--surface);
    }
    .doc-row-upload:hover {
      background: var(--surface-sunken);
    }
    .upload-error {
      color: var(--status-danger);
      font-size: var(--font-size-sm);
      margin: var(--space-2) 0 0;
    }
    .submit-btn {
      display: inline-block;
      margin-top: var(--space-3);
    }
  `,
})
export class DocUploadComponent {
  private readonly service = inject(ExpedienteService);
  private readonly agenda = inject(DocumentAgendaService);

  readonly caseId = input.required<number>();
  /**
   * An insured with policies in several insurers may be uploading to a case outside their session's
   * default tenant (see `ExpedienteService.getById`). Null for analysts and referents.
   */
  readonly insurerSlug = input<string | null>(null);
  /** With branch and claim cause, slots come from the document agenda; otherwise, the full catalog. */
  readonly branch = input<string | null>(null);
  readonly claimCause = input<string | null>(null);
  readonly uploaded = output<void>();

  protected readonly slots = signal<DocUploadSlot[]>([]);

  private readonly requiredTypes = toSignal(
    toObservable(computed(() => ({ branch: this.branch(), claimCause: this.claimCause() }))).pipe(
      switchMap(({ branch, claimCause }) =>
        (branch && claimCause
          ? this.agenda.slotsForBranch(branch, claimCause)
          : of(CASE_DOCUMENT_TYPES)
        ).pipe(
          map((value): FetchState<readonly CaseDocumentType[]> => ({ status: 'ok', value })),
          startWith<FetchState<readonly CaseDocumentType[]>>({ status: 'loading' }),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } },
  );

  /** The agenda lists everything required, not what is still missing, so uploaded types are subtracted. */
  private readonly uploadedTypes = toSignal(
    toObservable(computed(() => ({ caseId: this.caseId(), insurerSlug: this.insurerSlug() }))).pipe(
      switchMap(({ caseId, insurerSlug }) =>
        this.service.listDocuments(caseId, insurerSlug).pipe(
          map((docs): FetchState<Set<string>> => ({
            status: 'ok',
            value: new Set(docs.map((d) => d.type)),
          })),
          catchError(() => of<FetchState<Set<string>>>({ status: 'ok', value: new Set() })),
          startWith<FetchState<Set<string>>>({ status: 'loading' }),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } },
  );

  protected readonly loadingSlots = computed(
    () => this.requiredTypes().status === 'loading' || this.uploadedTypes().status === 'loading',
  );

  private readonly pendingTypes = computed<readonly CaseDocumentType[]>(() => {
    const required = this.requiredTypes();
    const uploaded = this.uploadedTypes();
    if (required.status === 'loading' || uploaded.status === 'loading') return [];
    return required.value.filter(({ type }) => !uploaded.value.has(type));
  });

  constructor() {
    // Rebuilding the slots discards files picked but not yet sent.
    effect(() => {
      const types = this.pendingTypes();
      this.slots.set(types.map(({ type, label }) => ({ type, label, file: null })));
    });
  }

  protected readonly uploading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly dragOverIndex = signal<number | null>(null);
  protected readonly selectedCount = computed(() => this.slots().filter((s) => s.file).length);

  private static readonly MAX_SIZE_BYTES = 10 * 1024 * 1024;
  private static readonly ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'application/pdf'];

  private validate(file: File): string | null {
    if (!DocUploadComponent.ACCEPTED_TYPES.includes(file.type)) {
      return 'Solo se aceptan archivos JPG, PNG o PDF.';
    }
    if (file.size > DocUploadComponent.MAX_SIZE_BYTES) {
      return 'El archivo supera el límite de 10 MB.';
    }
    return null;
  }

  private assignFile(index: number, file: File): void {
    const validationError = this.validate(file);
    if (validationError) {
      this.error.set(validationError);
      return;
    }
    this.error.set(null);
    this.slots.update((slots) => {
      const updated = [...slots];
      updated[index] = { ...updated[index], file };
      return updated;
    });
  }

  protected onFileChange(index: number, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    if (file) {
      this.assignFile(index, file);
    }
  }

  protected onDragOver(event: DragEvent, index: number): void {
    event.preventDefault();
    this.dragOverIndex.set(index);
  }

  protected onDragLeave(): void {
    this.dragOverIndex.set(null);
  }

  protected onDrop(event: DragEvent, index: number): void {
    event.preventDefault();
    this.dragOverIndex.set(null);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      this.assignFile(index, file);
    }
  }

  protected removeFile(index: number): void {
    this.slots.update((slots) => {
      const updated = [...slots];
      updated[index] = { ...updated[index], file: null };
      return updated;
    });
  }

  protected submit(): void {
    if (this.uploading()) return;

    const docs = new Map<string, File>();
    for (const slot of this.slots()) {
      if (slot.file) docs.set(slot.type, slot.file);
    }
    if (docs.size === 0) return;

    this.uploading.set(true);
    this.error.set(null);

    this.service.uploadDocuments(this.caseId(), docs, this.insurerSlug()).subscribe({
      next: () => {
        this.uploading.set(false);
        this.uploaded.emit();
      },
      error: (err) => {
        this.uploading.set(false);
        this.error.set(err.error?.detail || 'Error al subir documentos');
      },
    });
  }
}
