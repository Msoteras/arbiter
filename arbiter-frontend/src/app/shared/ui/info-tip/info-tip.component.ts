import { ChangeDetectionStrategy, Component, ElementRef, inject, signal } from '@angular/core';

import { OverlayPosition, anchorToTrigger } from '../overlay-position';

/**
 * Ícono "i" al lado de un label, para una aclaración puntual que no amerita un
 * `.section-hint` fijo ocupando espacio todo el tiempo (ej. una sola palabra del
 * formulario, no todo un bloque). El texto va por content projection.
 *
 * Toggle por click en vez de solo hover: en mobile no hay hover, y así funciona
 * igual con teclado (foco + Enter/Space, nativo de `<button>`) que con mouse o touch.
 *
 * La burbuja es `fixed` y se ancla con {@link anchorToTrigger}, igual que los menús y los selects
 * del kit: siendo `absolute` la recortaba cualquier ancestro con overflow, y además abría siempre
 * hacia arriba — con un texto que no entraba arriba del ícono, se veía cortada. Ahora cae hacia el
 * lado donde hay lugar.
 */
@Component({
  selector: 'app-info-tip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:click)': 'onDocumentClick()',
  },
  template: `
    <span class="info-tip">
      <button
        type="button"
        class="trigger"
        [attr.aria-expanded]="open()"
        [attr.aria-label]="open() ? 'Ocultar información' : 'Más información'"
        (click)="toggle($event)"
      >
        <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.3" />
          <path d="M8 7.2v4.1" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
          <circle cx="8" cy="4.9" r="0.9" fill="currentColor" />
        </svg>
      </button>
      @if (open()) {
        <span
          class="bubble"
          role="tooltip"
          [style.top.px]="pos().top"
          [style.bottom.px]="pos().bottom"
          [style.left.px]="pos().left"
          [style.right.px]="pos().right"
        ><ng-content /></span>
      }
    </span>
  `,
  styles: `
    .info-tip { position: relative; display: inline-flex; vertical-align: middle; margin-left: var(--space-1); }
    .trigger {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 18px;
      height: 18px;
      padding: 0;
      border: none;
      background: none;
      border-radius: var(--radius-pill);
      color: var(--text-muted);
      cursor: pointer;
    }
    .trigger:hover, .trigger[aria-expanded='true'] { color: var(--text-secondary); }
    .trigger:focus-visible { outline: none; box-shadow: var(--focus-ring); }
    .trigger svg { width: 15px; height: 15px; }

    .bubble {
      position: fixed;
      z-index: 50;
      width: max-content;
      /* min() y no un ancho fijo: en mobile 260px se sale de pantalla anclado a la izquierda. */
      max-width: min(280px, calc(100vw - var(--space-4) * 2));
      padding: var(--space-2) var(--space-3);
      border-radius: var(--radius-ctl);
      border: 1px solid var(--border-default);
      background: var(--surface-head);
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-regular);
      line-height: 1.4;
      box-shadow: var(--shadow-pop);
    }
  `,
})
export class InfoTipComponent {
  /** Alto estimado de la burbuja para decidir el lado; no hace falta medirla antes de dibujarla. */
  private static readonly ESTIMATED_HEIGHT = 140;

  private readonly host = inject(ElementRef<HTMLElement>);

  protected readonly open = signal(false);
  /** Coordenadas de viewport (la burbuja es `fixed`). Null en el eje que no se fija. */
  protected readonly pos = signal<OverlayPosition>({
    top: null,
    bottom: null,
    left: null,
    right: null,
    width: null,
  });

  protected toggle(event: MouseEvent): void {
    event.stopPropagation();
    if (!this.open()) {
      const trigger = this.host.nativeElement.getBoundingClientRect();
      // Se ancla al borde más lejano del costado de pantalla donde está el ícono: anclando
      // siempre a la izquierda, un tip del lado derecho se iba de pantalla — el mismo problema
      // que arriba, espejado.
      const align = trigger.left > document.documentElement.clientWidth / 2 ? 'end' : 'start';
      this.pos.set(
        anchorToTrigger(trigger, InfoTipComponent.ESTIMATED_HEIGHT, align),
      );
    }
    this.open.update((v) => !v);
  }

  /** Cierra al clickear afuera — mismo criterio que un menú/dropdown del kit. */
  protected onDocumentClick(): void {
    if (this.open()) {
      this.open.set(false);
    }
  }
}
