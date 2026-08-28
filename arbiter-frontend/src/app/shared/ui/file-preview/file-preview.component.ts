import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

import { formatFileSize, isPreviewableImage, isPreviewablePdf } from '../../../core/models/case-document';

/**
 * Vista previa de un archivo que el usuario acaba de elegir, ANTES de subirlo.
 *
 * A diferencia del visor del expediente, acá no hay backend: el File ya está en memoria,
 * así que alcanza con un object URL. Sirve para no mandar la foto movida o el PDF
 * equivocado y tener que repetir el ciclo de "falta documentación".
 *
 * Miniatura siempre visible + click para agrandar en línea (sin modal ni pestaña nueva).
 */
@Component({
  selector: 'app-file-preview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="preview">
      <button
        type="button"
        class="thumb"
        [class.expandable]="canPreview()"
        [attr.aria-expanded]="canPreview() ? expanded() : null"
        [disabled]="!canPreview()"
        (click)="toggle()">
        @if (isImage() && url()) {
          <img [src]="url()!" [alt]="file().name" />
        } @else {
          <span class="doc-icon" aria-hidden="true">PDF</span>
        }
      </button>

      <div class="meta">
        <span class="name" [title]="file().name">{{ file().name }}</span>
        <span class="size">{{ size() }}</span>
        @if (canPreview()) {
          <button type="button" class="toggle" (click)="toggle()">
            {{ expanded() ? 'Ocultar' : 'Ver más grande' }}
          </button>
        }
      </div>
    </div>

    @if (expanded() && url()) {
      @if (isImage()) {
        <div class="expanded image"><img [src]="url()!" [alt]="file().name" /></div>
      } @else if (isPdf() && safeUrl()) {
        <iframe class="expanded pdf" [src]="safeUrl()!" [title]="file().name"></iframe>
      }
    }
  `,
  styles: `
    :host { display: block; }

    .preview { display: flex; align-items: center; gap: var(--space-3); }

    .thumb {
      flex-shrink: 0;
      width: 56px;
      height: 56px;
      display: grid;
      place-items: center;
      overflow: hidden;
      padding: 0;
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: var(--surface-sunken);
    }
    .thumb.expandable { cursor: pointer; transition: border-color 0.1s, transform 0.1s; }
    .thumb.expandable:hover { border-color: var(--border-strong); }
    .thumb.expandable:active { transform: scale(0.96); }
    .thumb img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      outline: 1px solid rgb(0 0 0 / 0.1);
      outline-offset: -1px;
    }
    .doc-icon {
      font-size: var(--font-size-2xs);
      font-weight: var(--font-weight-medium);
      letter-spacing: 0.04em;
      color: var(--text-muted);
    }

    .meta { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .name {
      font-size: var(--font-size-sm);
      font-family: var(--font-mono);
      color: var(--text-tertiary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .size { font-size: var(--font-size-xs); color: var(--text-muted); font-variant-numeric: tabular-nums; }

    .toggle {
      align-self: flex-start;
      padding: 0;
      border: none;
      background: none;
      cursor: pointer;
      font: inherit;
      font-size: var(--font-size-xs);
      color: var(--accent-blue);
    }
    .toggle:hover { text-decoration: underline; }

    .expanded {
      display: block;
      width: 100%;
      margin-top: var(--space-3);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-ctl);
      background: var(--surface-sunken);
    }
    .expanded.image { display: grid; place-items: center; padding: var(--space-3); }
    /* Sin radio: el marco redondea 7px pero tiene 12px de padding, así que el borde de la
       imagen nunca toca la curva. Contorno negro puro al 10% — un neutro teñido se lee
       como suciedad en el borde. */
    .expanded.image img {
      max-width: 100%;
      max-height: 420px;
      object-fit: contain;
      outline: 1px solid rgb(0 0 0 / 0.1);
      outline-offset: -1px;
    }
    .expanded.pdf { height: 420px; }
  `,
})
export class FilePreviewComponent {
  private readonly sanitizer = inject(DomSanitizer);
  private readonly destroyRef = inject(DestroyRef);

  readonly file = input.required<File>();

  protected readonly url = signal<string | null>(null);
  protected readonly safeUrl = signal<SafeResourceUrl | null>(null);
  protected readonly expanded = signal(false);

  protected readonly isImage = computed(() => isPreviewableImage(this.file().type));
  protected readonly isPdf = computed(() => isPreviewablePdf(this.file().type));
  protected readonly canPreview = computed(() => this.isImage() || this.isPdf());
  protected readonly size = computed(() => formatFileSize(this.file().size));

  constructor() {
    // Un object URL nuevo por archivo; el anterior se revoca o queda colgado en memoria.
    effect((onCleanup) => {
      const file = this.file();
      const objectUrl = URL.createObjectURL(file);
      this.url.set(objectUrl);
      this.safeUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(objectUrl));
      this.expanded.set(false);
      onCleanup(() => URL.revokeObjectURL(objectUrl));
    });

    this.destroyRef.onDestroy(() => {
      const current = this.url();
      if (current) URL.revokeObjectURL(current);
    });
  }

  protected toggle(): void {
    if (this.canPreview()) this.expanded.update((v) => !v);
  }
}
