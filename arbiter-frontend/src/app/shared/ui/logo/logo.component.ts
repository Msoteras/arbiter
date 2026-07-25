import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Isotipo de Arbiter: aro con asterisco de 8 puntas y "dona" inferior,
 * ambos apoyados sobre recortes del aro (via mask). Hereda el color del
 * contexto (currentColor) y escala al ancho del host — el consumidor
 * define tamaño vía CSS (ej. `app-logo { width: 28px; }`).
 */
@Component({
  selector: 'app-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg viewBox="0 0 48 48" fill="none" aria-hidden="true">
      <defs>
        <mask id="arb-logo-gaps">
          <rect width="48" height="48" fill="white" />
          <circle cx="36.5" cy="11.5" r="9.5" fill="black" />
          <circle cx="11" cy="38" r="8.5" fill="black" />
        </mask>
      </defs>
      <circle
        cx="24"
        cy="25"
        r="17"
        stroke="currentColor"
        stroke-width="3.6"
        mask="url(#arb-logo-gaps)"
      />
      <!-- Asterisco tipográfico de 6 brazos (3 líneas a 60°). -->
      <g stroke="currentColor" stroke-width="3.6" stroke-linecap="round">
        <line x1="36.5" y1="5.7" x2="36.5" y2="17.3" />
        <line x1="31.5" y1="8.6" x2="41.5" y2="14.4" />
        <line x1="41.5" y1="8.6" x2="31.5" y2="14.4" />
      </g>
      <circle cx="11" cy="38" r="5" stroke="currentColor" stroke-width="3" />
      <circle cx="11" cy="38" r="1.8" fill="currentColor" />
    </svg>
  `,
  styles: `
    :host { display: inline-block; line-height: 0; }
    svg { display: block; width: 100%; height: auto; }
  `,
})
export class LogoComponent {}
