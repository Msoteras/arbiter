import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';

import { ExpedienteService } from '../../../features/expedientes/expediente.service';

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
  template: `
    <p class="muted">
      La evaluación indica que faltan documentos requeridos. Subí la documentación
      faltante para que el caso se vuelva a evaluar.
    </p>

    @for (slot of slots(); track slot.type; let i = $index) {
      <div class="doc-row">
        <span class="doc-row-label">{{ slot.label }}</span>
        @if (slot.file) {
          <div class="doc-row-file">
            <span class="doc-row-filename">{{ slot.file.name }}</span>
            <button type="button" class="doc-row-remove" (click)="removeFile(i)">✕</button>
          </div>
        } @else {
          <label class="doc-row-upload">
            Elegir archivo
            <input type="file" accept="image/*,.pdf" (change)="onFileChange(i, $event)" hidden />
          </label>
        }
      </div>
    }

    @if (error()) {
      <p class="upload-error">{{ error() }}</p>
    }

    <button
      class="btn-pri"
      [disabled]="selectedCount() === 0 || uploading()"
      (click)="submit()">
      {{ uploading() ? 'Enviando…' : 'Enviar documentación' }}
    </button>
  `,
  styles: `
    :host { display: block; }
    .muted { margin: 0 0 6px; color: var(--c-muted); font-size: 13px; }
    .doc-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 0;
      border-bottom: 1px solid var(--c-divider);
    }
    .doc-row-label { font-size: 13px; color: var(--c-ink-2); }
    .doc-row-file { display: flex; align-items: center; gap: 8px; }
    .doc-row-filename { font-size: 12px; font-family: var(--font-mono); color: var(--c-ink-3); }
    .doc-row-remove {
      border: none;
      background: none;
      cursor: pointer;
      color: var(--c-muted);
      font-size: 12px;
      padding: 2px 6px;
    }
    .doc-row-remove:hover { color: var(--c-ink); }
    .doc-row-upload {
      font-size: 12px;
      border: 1px solid var(--c-border-3);
      border-radius: var(--radius-ctl);
      padding: 5px 12px;
      cursor: pointer;
      color: var(--c-ink-3);
      background: var(--c-bg);
    }
    .doc-row-upload:hover { background: var(--c-bg-soft-2); }
    .upload-error { color: var(--c-ink); font-size: 12px; margin: 8px 0 0; }
    .btn-pri {
      margin-top: 12px;
      border: 1px solid var(--c-ink);
      background: var(--c-ink);
      color: var(--c-bg);
      border-radius: var(--radius-ctl);
      padding: 7px 16px;
      font-size: 13px;
      cursor: pointer;
    }
    .btn-pri:disabled { opacity: 0.5; cursor: default; }
  `,
})
export class DocUploadComponent {
  private readonly service = inject(ExpedienteService);

  readonly caseId = input.required<number>();
  /** Se emite cuando el backend aceptó los documentos (el caso vuelve a clasificación). */
  readonly uploaded = output<void>();

  protected readonly slots = signal<DocUploadSlot[]>([
    { type: 'police_report', label: 'Denuncia policial', file: null },
    { type: 'item_photo', label: 'Foto del bien', file: null },
    { type: 'invoice', label: 'Factura de compra', file: null },
    { type: 'quote', label: 'Presupuesto de reparación', file: null },
  ]);

  protected readonly uploading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly selectedCount = computed(() => this.slots().filter((s) => s.file).length);

  protected onFileChange(index: number, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.slots.update((slots) => {
      const updated = [...slots];
      updated[index] = { ...updated[index], file };
      return updated;
    });
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

    this.service.uploadDocuments(this.caseId(), docs).subscribe({
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
