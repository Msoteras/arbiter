import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

/**
 * Interruptor de encendido/apagado del design system.
 *
 * Va donde la decisión es "esto corre o no corre" y el efecto es inmediato sobre lo que hay al lado
 * (una regla del motor, un factor del scoring). Para elegir de una lista está `app-select`; para
 * marcar items de un conjunto, `app-checkbox`; para filtrar o alternar una vista, el chip.
 *
 * El estado se lee por la posición de la perilla además de por el color, así que no depende de
 * distinguir el teal del gris. `role="switch"` + `aria-checked` lo dejan anunciado como
 * interruptor; el nombre va en `ariaLabel` porque en las pantallas donde se usa el rótulo visible
 * es un elemento aparte, al lado.
 */
@Component({
  selector: 'app-switch',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      class="track"
      role="switch"
      [attr.aria-checked]="checked()"
      [attr.aria-label]="ariaLabel()"
      [disabled]="disabled()"
      (click)="toggle()"
    >
      <span class="knob" aria-hidden="true"></span>
    </button>
  `,
  styles: `
    :host { display: inline-block; }
    .track {
      display: block;
      width: 40px;
      height: 22px;
      padding: 2px;
      border: 1px solid transparent;
      border-radius: var(--radius-pill);
      background: var(--border-control);
      cursor: pointer;
      transition: background-color var(--dur-1) ease;
    }
    .knob {
      display: block;
      width: 16px;
      height: 16px;
      border-radius: var(--radius-pill);
      /* Blanca en los dos estados: es la perilla, no el estado. Lo que cambia es dónde está. */
      background: var(--text-on-emphasis);
      box-shadow: var(--shadow-card);
      transition: transform var(--dur-1) ease;
    }
    /* --accent-fill y no --action-accent-bg: ese es el teal FUERTE del botón sólido, pensado para
       que un texto blanco encima pase AA, y en un toggle se veía apagado. El sistema ya dice que
       los rellenos activos (y nombra los toggles) van con el teal de marca; --accent-fill es ese
       rol, con su borde para que el control se distinga del fondo. */
    .track[aria-checked='true'] {
      background: var(--accent-fill);
      border-color: var(--accent-fill-border);
    }
    .track[aria-checked='true'] .knob { transform: translateX(18px); }
    .track:disabled { cursor: default; opacity: 0.55; }
    .track:focus-visible { outline: none; box-shadow: var(--focus-ring); }

    @media (prefers-reduced-motion: reduce) {
      .track, .knob { transition: none; }
    }
  `,
})
export class SwitchComponent {
  /** Valor two-way: `[(checked)]`. */
  readonly checked = model(false);
  readonly disabled = input(false);
  /** Nombre accesible: el rótulo visible suele ser un elemento hermano, no contenido del control. */
  readonly ariaLabel = input<string | null>(null);

  protected toggle(): void {
    if (!this.disabled()) {
      this.checked.set(!this.checked());
    }
  }
}
