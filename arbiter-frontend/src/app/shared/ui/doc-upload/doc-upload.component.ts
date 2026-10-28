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
import { catchError, map, startWith, switchMap } from 'rxjs';

import { CaseService } from '../../../features/cases/case.service';
import { DocumentAgendaService } from '../../../features/cases/document-agenda.service';
import { DocumentTypeService } from '../../../core/services/document-type.service';
import { PendingDocument } from '../../../core/models/case-document';
import { ButtonComponent } from '../button/button.component';
import { FilePreviewComponent } from '../file-preview/file-preview.component';
import { InlineLoadingComponent } from '../inline-loading/inline-loading.component';

interface DocUploadSlot {
  type: string;
  /** What the analyst wrote, for a document the catalog doesn't name; otherwise the catalog names it. */
  description: string | null;
  file: File | null;
}

/**
 * `requireAll` is false only on the fallback: with no pending list the whole catalog is offered,
 * and asking for all of it would leave the insured unable to send anything.
 */
type SlotsState =
  { status: 'loading' } | { status: 'ok'; slots: readonly PendingDocument[]; requireAll: boolean };

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
        @if (requireAll()) {
          Subí todos los documentos para que el caso se vuelva a evaluar.
        } @else {
          Subí la documentación faltante para que el caso se vuelva a evaluar.
        }
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
          <span class="doc-row-label">{{ slotLabel(slot) }}</span>
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

      <app-button class="submit-btn" [disabled]="!canSubmit()" (click)="submit()">
        {{ uploading() ? 'Enviando…' : 'Enviar documentación' }}
      </app-button>
    }
  `,
  styles: `
    @use 'media';

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
      flex: 0 0 40%;
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
    @include media.touch {
      .doc-row-upload,
      .doc-row-remove {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-height: var(--touch-target);
        min-width: var(--touch-target);
      }
    }
    @include media.phone {
      .doc-row {
        flex-direction: column;
        align-items: stretch;
        gap: var(--space-2);
      }
      .doc-row-label {
        flex: none;
      }
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
  private readonly service = inject(CaseService);
  private readonly agenda = inject(DocumentAgendaService);
  private readonly documentTypes = inject(DocumentTypeService);

  readonly caseId = input.required<number>();
  /**
   * An insured with policies in several insurers may be uploading to a case outside their session's
   * default tenant (see `CaseService.getById`). Null for analysts and referents.
   */
  readonly insurerSlug = input<string | null>(null);
  readonly uploaded = output<void>();

  protected readonly slots = signal<DocUploadSlot[]>([]);

  /**
   * The backend says what is pending: the analyst's request, or what the schedule still misses. An
   * `other_<n>` document is named by what the analyst wrote.
   */
  private readonly pending = toSignal(
    toObservable(computed(() => ({ caseId: this.caseId(), insurerSlug: this.insurerSlug() }))).pipe(
      switchMap(({ caseId, insurerSlug }) =>
        this.service.pendingDocuments(caseId, insurerSlug).pipe(
          map((docs): SlotsState => ({ status: 'ok', slots: docs, requireAll: true })),
          catchError(() =>
            this.agenda.catalogSlots().pipe(
              map((catalog): SlotsState => ({
                status: 'ok',
                slots: catalog.map(({ type }) => ({ type, description: null })),
                requireAll: false,
              })),
            ),
          ),
          startWith<SlotsState>({ status: 'loading' }),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as SlotsState },
  );

  protected readonly loadingSlots = computed(() => this.pending().status === 'loading');

  protected readonly requireAll = computed(() => {
    const state = this.pending();
    return state.status === 'ok' && state.requireAll;
  });

  constructor() {
    // Rebuilding the slots discards files picked but not yet sent.
    effect(() => {
      const state = this.pending();
      const types = state.status === 'ok' ? state.slots : [];
      this.slots.set(types.map(({ type, description }) => ({ type, description, file: null })));
    });
  }

  /** In the template, not in the pipe: the catalog may arrive after the pending list. */
  protected slotLabel(slot: DocUploadSlot): string {
    return slot.description ?? this.documentTypes.label(slot.type);
  }

  protected readonly uploading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly dragOverIndex = signal<number | null>(null);
  protected readonly selectedCount = computed(() => this.slots().filter((s) => s.file).length);

  /** What was asked is the minimum, so it goes whole: every slot, unless on the catalog fallback. */
  protected readonly canSubmit = computed(() => {
    if (this.uploading() || this.selectedCount() === 0) return false;
    return !this.requireAll() || this.selectedCount() === this.slots().length;
  });

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
    if (!this.canSubmit()) return;

    const docs = new Map<string, File>();
    for (const slot of this.slots()) {
      if (slot.file) docs.set(slot.type, slot.file);
    }

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
