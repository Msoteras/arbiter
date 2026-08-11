import { ChangeDetectionStrategy, Component, input } from '@angular/core';

type Variant = 'default' | 'soft' | 'ai';

/**
 * Contenedor con borde + radio. `soft` = fondo tenue; `ai` = lavado teal para las
 * tarjetas del modelo (sugerencias/recomendaciones, marcadas con ✦).
 * Cabecera opcional vía `heading` (+ `icon` opcional, ej. '✦').
 * El cuerpo va como contenido proyectado.
 */
@Component({
  selector: 'app-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="card"
      [class.soft]="variant() === 'soft'"
      [class.ai]="variant() === 'ai'"
      [class.flush]="flush()"
      [class.bare]="bare()"
    >
      @if (heading()) {
        <div class="card-head">
          @if (icon()) {
            <span class="icon" aria-hidden="true">{{ icon() }}</span>
          }
          <h2 class="card-title">{{ heading() }}</h2>
        </div>
      }
      <ng-content />
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    .card {
      border: 1px solid var(--border-default);
      border-radius: var(--radius-card);
      background: var(--surface);
      padding: var(--space-4);
      box-shadow: var(--shadow-card);
      /* min-height, no height: llena la celda si el host se estira (grid/flex), pero nunca
         achica la card por debajo de su contenido (con height:100% pasaba dentro de un flex
         column de altura auto, ej. .bandeja, y .card.flush lo escondía con su overflow). */
      min-height: 100%;
    }
    .card.soft {
      background: var(--surface-soft);
    }
    .card.ai {
      background: var(--surface-ai);
      border-color: var(--border-ai);
    }
    /* Para cards que son un enlace (ej. la lista del portal). El host es el <a>: acá solo
       se agrega la respuesta al hover, el resto del tratamiento ya lo da .card. */
    :host(.interactive) .card {
      transition:
        background-color 0.1s,
        border-color 0.1s;
    }
    :host(.interactive:hover) .card {
      background: var(--surface-soft);
      border-color: var(--border-strong);
    }
    /* Sin caja: ni borde ni fondo ni padding. Para reusar el contenido de una card cuando el
       contenedor ya ES la caja (ej. el wizard dentro de un modal) y una segunda no suma. */
    .card.bare {
      border: none;
      box-shadow: none;
      background: transparent;
      padding: 0;
      min-height: 0;
    }
    /* Sin padding interno: para contenido que necesita llegar al borde (ej. una tabla). */
    .card.flush {
      padding: 0;
      overflow-x: auto;
    }
    .card.flush .card-head {
      margin: var(--space-4) var(--space-4) var(--space-3);
    }
    .card-head {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      margin-bottom: var(--space-3);
    }
    .icon {
      color: var(--text-primary);
    }
    .card.ai .card-head .icon {
      color: var(--accent-fg);
    }
    .card.ai .card-head .card-title {
      color: var(--accent-fg);
    }
    .card-title {
      margin: 0;
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-medium);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-tertiary);
    }
  `,
})
export class CardComponent {
  readonly variant = input<Variant>('default');
  readonly heading = input('');
  readonly icon = input('');
  readonly flush = input(false);
  /** Sin caja (borde/fondo/padding): el contenedor externo ya provee la caja. */
  readonly bare = input(false);
}
