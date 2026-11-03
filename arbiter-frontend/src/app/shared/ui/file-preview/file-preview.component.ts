import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  ElementRef,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

import { ButtonComponent } from '../button/button.component';
import { ModalComponent } from '../modal/modal.component';
import {
  formatFileSize,
  isPreviewableImage,
  isPreviewablePdf,
} from '../../../core/models/case-document';

/**
 * Preview of a locally selected file before upload, served from an object URL. The large view opens
 * in a modal: inline, it would stretch whatever row hosts the thumbnail.
 */
@Component({
  selector: 'app-file-preview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonComponent, ModalComponent],
  template: `
    <div class="preview">
      <button
        type="button"
        class="thumb"
        [class.expandable]="canPreview()"
        [attr.aria-label]="canPreview() ? 'Ver ' + file().name : null"
        [disabled]="!canPreview()"
        (click)="toggle()"
      >
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
          <button type="button" class="toggle" (click)="toggle()">Ver más grande</button>
        }
      </div>
    </div>

    <app-modal
      [open]="expanded() && !!url()"
      [heading]="file().name"
      size="lg"
      [hideActions]="!fullscreenEnabled"
      (close)="expanded.set(false)"
    >
      @if (isImage()) {
        <div #viewer class="expanded image"><img [src]="url()!" [alt]="file().name" /></div>
      } @else if (isPdf() && safeUrl()) {
        <iframe #viewer class="expanded pdf" [src]="safeUrl()!" [title]="file().name"></iframe>
      }
      <ng-container modalActions>
        <app-button variant="secondary" size="sm" (click)="fullscreen()"
          >Pantalla completa</app-button
        >
      </ng-container>
    </app-modal>
  `,
  styles: `
    :host {
      display: block;
    }

    .preview {
      display: flex;
      align-items: center;
      gap: var(--space-3);
    }

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
    .thumb.expandable {
      cursor: pointer;
      transition:
        border-color 0.1s,
        transform 0.1s;
    }
    .thumb.expandable:hover {
      border-color: var(--border-strong);
    }
    .thumb.expandable:active {
      transform: scale(0.96);
    }
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

    .meta {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
    }
    .name {
      font-size: var(--font-size-sm);
      font-family: var(--font-mono);
      color: var(--text-tertiary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .size {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      font-variant-numeric: tabular-nums;
    }

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
    .toggle:hover {
      text-decoration: underline;
    }

    .expanded {
      display: block;
      width: 100%;
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-ctl);
      background: var(--surface-sunken);
    }
    .expanded.image {
      display: grid;
      place-items: center;
      padding: var(--space-3);
    }
    .expanded.image img {
      max-width: 100%;
      max-height: 70dvh;
      object-fit: contain;
      outline: 1px solid rgb(0 0 0 / 0.1);
      outline-offset: -1px;
    }
    .expanded.pdf {
      height: 70dvh;
    }
    .expanded:fullscreen {
      border: none;
      border-radius: 0;
    }
    .expanded.image:fullscreen img {
      max-height: 100%;
      outline: none;
    }
  `,
})
export class FilePreviewComponent {
  private readonly sanitizer = inject(DomSanitizer);
  private readonly destroyRef = inject(DestroyRef);

  readonly file = input.required<File>();

  protected readonly url = signal<string | null>(null);
  protected readonly safeUrl = signal<SafeResourceUrl | null>(null);
  protected readonly expanded = signal(false);

  /** False on iPhone Safari, which only takes video full screen: the button stays hidden there. */
  protected readonly fullscreenEnabled = document.fullscreenEnabled;
  private readonly viewer = viewChild<ElementRef<HTMLElement>>('viewer');

  protected readonly isImage = computed(() => isPreviewableImage(this.file().type));
  protected readonly isPdf = computed(() => isPreviewablePdf(this.file().type));
  protected readonly canPreview = computed(() => this.isImage() || this.isPdf());
  protected readonly size = computed(() => formatFileSize(this.file().size));

  constructor() {
    // Revoke the previous object URL, or it leaks.
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

  protected fullscreen(): void {
    void this.viewer()?.nativeElement.requestFullscreen();
  }

  protected toggle(): void {
    if (this.canPreview()) this.expanded.update((v) => !v);
  }
}
