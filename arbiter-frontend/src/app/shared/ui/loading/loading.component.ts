import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { SpinnerComponent } from '../spinner/spinner.component';

/**
 * Pantalla de carga de marca: el símbolo de Arbiter girando (app-spinner) + un mensaje contextual.
 * Se usa mientras una pantalla espera TODOS sus datos, en vez de dejar cada recuadro con su propio
 * "Cargando…". Overlay a viewport completo: tapa la sidebar/topbar y el contenido.
 */
@Component({
  selector: 'app-loading',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpinnerComponent],
  template: `
    <div class="loading" role="status" aria-live="polite">
      <app-spinner [size]="72" />
      <div class="loading-text">
        <p class="msg">
          {{ message()
          }}<span class="dots" aria-hidden="true"><span>.</span><span>.</span><span>.</span></span>
        </p>
        @if (sub()) {
          <p class="sub">{{ sub() }}</p>
        }
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    /* Pantalla de carga a viewport completo: tapa la sidebar/topbar y el contenido, centrada sobre
       el lienzo de la app. position:fixed para cubrir todo aunque el loader se renderice dentro de
       una pantalla puntual. */
    .loading {
      position: fixed;
      inset: 0;
      z-index: 200;
      background: var(--surface-sunken);
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--space-5);
      color: var(--text-primary);
      padding: var(--space-6);
      text-align: center;
    }
    .loading-text {
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
      align-items: center;
    }
    .msg {
      font-size: var(--font-size-md);
      font-weight: var(--font-weight-medium);
      color: var(--text-secondary);
    }
    /* Segunda línea, tenue: una cortesía ("Aguardá un momento") bajo el mensaje principal. */
    .sub {
      font-size: var(--font-size-sm);
      color: var(--text-muted);
    }
    /* Los tres puntos que laten mientras carga. */
    .dots span {
      animation: arb-dots 1.2s ease-in-out infinite;
    }
    .dots span:nth-child(2) {
      animation-delay: 0.18s;
    }
    .dots span:nth-child(3) {
      animation-delay: 0.36s;
    }
    @keyframes arb-dots {
      0%,
      100% {
        opacity: 0.25;
      }
      50% {
        opacity: 1;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .dots span {
        animation: none;
        opacity: 0.6;
      }
    }
  `,
})
export class LoadingComponent {
  /** Mensaje contextual, ej. "Preparando tu espacio de trabajo". */
  readonly message = input('Cargando');
  /** Segunda línea opcional, tenue (ej. "Aguardá un momento"). */
  readonly sub = input('');
}
