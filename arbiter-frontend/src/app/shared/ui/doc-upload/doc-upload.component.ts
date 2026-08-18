import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { of, switchMap } from 'rxjs';

import { ExpedienteService } from '../../../features/expedientes/expediente.service';
import { DocumentAgendaService } from '../../../features/expedientes/document-agenda.service';
import { CASE_DOCUMENT_TYPES, CaseDocumentType } from '../../../core/models/case-document';
import { ButtonComponent } from '../button/button.component';
import { FilePreviewComponent } from '../file-preview/file-preview.component';

interface DocUploadSlot {
  type: string;
  label: string;
  file: File | null;
}

/**
 * Carga de documentación faltante para un expediente en AWAITING_DOCUMENTATION.
 * Sube los archivos vía POST /cases/{id}/documents (lo que re-dispara la
 * clasificación en el backend) y emite `uploaded` para que el padre refresque.
 */
@Component({
  selector: 'app-doc-upload',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonComponent, FilePreviewComponent],
  template: `
    <p class="muted">
      La evaluación indica que faltan documentos requeridos. Subí la documentación
      faltante para que el caso se vuelva a evaluar.
    </p>
    <p class="hint">JPG, PNG o PDF · hasta 10 MB por archivo</p>

    @for (slot of slots(); track slot.type; let i = $index) {
      <div
        class="doc-row"
        [class.dragover]="dragOverIndex() === i"
        (dragover)="onDragOver($event, i)"
        (dragleave)="onDragLeave()"
        (drop)="onDrop($event, i)">
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
      (click)="submit()">
      {{ uploading() ? 'Enviando…' : 'Enviar documentación' }}
    </app-button>
  `,
  styles: `
    :host { display: block; }
    .muted { margin: 0 0 var(--space-2); color: var(--text-muted); font-size: var(--font-size-body); }
    .hint { margin: 0 0 var(--space-3); color: var(--text-muted); font-size: var(--font-size-sm); }
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
    .doc-row.dragover { background: var(--surface-sunken); }
    .doc-row-label { font-size: var(--font-size-body); color: var(--text-secondary); }
    /* La miniatura crece hasta ocupar el ancho libre: al expandirla, la vista previa
       necesita todo el espacio de la fila. */
    .doc-row-file { display: flex; align-items: flex-start; gap: var(--space-2); flex: 1; min-width: 0; }
    .doc-row-file app-file-preview { flex: 1; min-width: 0; }
    .doc-row-remove {
      border: none;
      background: none;
      cursor: pointer;
      color: var(--text-muted);
      font-size: var(--font-size-sm);
      padding: 2px 6px;
    }
    .doc-row-remove:hover { color: var(--text-primary); }
    .doc-row-upload {
      font-size: var(--font-size-sm);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      padding: var(--space-1) var(--space-3);
      cursor: pointer;
      color: var(--text-tertiary);
      background: var(--surface);
    }
    .doc-row-upload:hover { background: var(--surface-sunken); }
    .upload-error { color: var(--status-danger); font-size: var(--font-size-sm); margin: var(--space-2) 0 0; }
    .submit-btn { display: inline-block; margin-top: var(--space-3); }
  `,
})
export class DocUploadComponent {
  private readonly service = inject(ExpedienteService);
  private readonly agenda = inject(DocumentAgendaService);

  readonly caseId = input.required<number>();
  /**
   * De qué aseguradora es el expediente — un asegurado con pólizas en más de una compañía puede
   * estar subiendo documentación a un expediente que no vive en el tenant por defecto de su
   * sesión (ver `ExpedienteService.getById`). Null para el analista/referente.
   */
  readonly insurerSlug = input<string | null>(null);
  /**
   * Ramo y hecho generador del expediente: arman el uploader con los documentos que esa
   * combinación realmente requiere (la agenda del referente), no con el catálogo completo. Sin
   * ambos, cae al catálogo.
   */
  readonly branch = input<string | null>(null);
  readonly claimCause = input<string | null>(null);
  /** Se emite cuando el backend aceptó los documentos (el caso vuelve a clasificación). */
  readonly uploaded = output<void>();

  protected readonly slots = signal<DocUploadSlot[]>(
    CASE_DOCUMENT_TYPES.map(({ type, label }) => ({ type, label, file: null })),
  );

  /** Tipos requeridos del ramo + hecho generador (o el catálogo completo como fallback). */
  private readonly requiredTypes = toSignal(
    toObservable(computed(() => ({ branch: this.branch(), claimCause: this.claimCause() }))).pipe(
      switchMap(({ branch, claimCause }) =>
        branch && claimCause ? this.agenda.slotsForBranch(branch, claimCause) : of(CASE_DOCUMENT_TYPES),
      ),
    ),
    { initialValue: CASE_DOCUMENT_TYPES as readonly CaseDocumentType[] },
  );

  constructor() {
    // Al resolverse la agenda del ramo, rearma los slots con el vocabulario real (limpia archivos).
    effect(() => {
      const types = this.requiredTypes();
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
