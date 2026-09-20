import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { StatusTone } from '../../../core/models/status-tone';
import { RatePipe } from '../../pipes/rate.pipe';

/** Una categoría de la distribución, con su color del semáforo ya resuelto por el dominio. */
export interface DistributionItem {
  label: string;
  count: number;
  tone: StatusTone;
}

/** Cómo se dibuja la proporción. La lista va siempre; esto decide qué la acompaña. */
export type DistributionShape = 'bar' | 'ring';

/**
 * Una distribución como barra apilada —o como anillo— más su lista.
 *
 * La lista es la parte que no se negocia: con el nombre, el conteo y el porcentaje alineados en una
 * grilla, el número exacto queda al lado de la categoría. El dibujo de arriba da la proporción de
 * un vistazo; es el que cambia de forma.
 *
 * `shape="bar"` es el default y es lo que conviene con cuatro o cinco categorías en una tarjeta
 * angosta: comparar largos es más fácil que comparar arcos. `shape="ring"` está para cuando la
 * pantalla se lee como un tablero y la pregunta es "qué parte del total es esto" más que "cuál es
 * más grande que cuál" — la lista sigue al lado, así que nadie tiene que estimar un arco.
 *
 * Sin librería de gráficos en ninguna de las dos formas: divs con ancho porcentual y un SVG de dos
 * círculos. Un canvas acá sería más código, más peso y menos accesible.
 *
 * Vive en el kit y no en una feature porque lo usan dos: el tablero del referente y el reporte de
 * resolución.
 */
@Component({
  selector: 'app-distribution',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="dist" [class.with-ring]="shape() === 'ring' && total() === null">
      @if (total() === null) {
        @if (shape() === 'ring') {
          <!-- Radio 15.915 hace que la circunferencia mida 100: así cada porción se expresa
               directamente en porcentaje, y el offset de 25 arranca el anillo a las 12 en punto. -->
          <div class="ring-wrap">
            <svg class="ring" viewBox="0 0 42 42" role="img" [attr.aria-label]="summary()">
              <circle class="ring-track" cx="21" cy="21" r="15.915" />
              @for (slice of slices(); track slice.label) {
                <circle
                  class="ring-arc tone-{{ slice.tone }}"
                  cx="21"
                  cy="21"
                  r="15.915"
                  [style.opacity]="slice.weight"
                  [attr.stroke-dasharray]="slice.share * 100 + ' ' + (100 - slice.share * 100)"
                  [attr.stroke-dashoffset]="25 - slice.offset * 100"
                />
              }
            </svg>
            <!-- El total va en HTML y no como <text> del SVG: así el tamaño sale de la escala
                 tipográfica del sistema y no de una unidad de usuario del viewBox. -->
            <span class="ring-center" aria-hidden="true">
              <span class="ring-total tabular">{{ sum() }}</span>
              @if (centerLabel()) {
                <span class="ring-caption">{{ centerLabel() }}</span>
              }
            </span>
          </div>
        } @else {
          <div class="stack" role="img" [attr.aria-label]="summary()">
            @for (slice of slices(); track slice.label) {
              <span
                class="slice tone-{{ slice.tone }}"
                [style.width.%]="slice.share * 100"
                [class.faded]="slice.faded"
              ></span>
            }
          </div>
        }
      }

      <ul class="legend">
        @for (slice of slices(); track slice.label) {
          <li>
            <span
              class="dot tone-{{ slice.tone }}"
              [style.opacity]="shape() === 'ring' ? slice.weight : null"
              [class.faded]="shape() === 'bar' && slice.faded"
              aria-hidden="true"
            ></span>
            <span class="name">{{ slice.label }}</span>
            <span class="count tabular">{{ slice.count }}</span>
            <span class="share tabular">{{ slice.share | rate }}</span>
          </li>
        }
      </ul>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    /* Con anillo, dibujo y lista conviven lado a lado mientras haya ancho; abajo de eso se apilan,
       que es como entra en un teléfono. */
    .dist.with-ring {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-5);
    }
    .dist.with-ring .legend {
      flex: 1 1 var(--ring-size);
    }
    .stack {
      display: flex;
      width: 100%;
      height: var(--space-2);
      border-radius: var(--radius-pill);
      overflow: hidden;
      background: var(--surface-sunken);
    }
    .slice {
      display: block;
      height: 100%;
    }
    /* Dos categorías pueden caer en el mismo tono del semáforo. En vez de inventar un color que el
       design system no tiene, la repetición se atenúa: mismo color, misma familia, distinguible. */
    .stack + .legend {
      margin-top: var(--space-4);
    }
    .slice.faded,
    .dot.faded {
      opacity: 0.5;
    }

    /* ── Anillo ─────────────────────────────────────────────────────────────── */
    .ring-wrap {
      position: relative;
      flex: 0 0 auto;
      width: var(--ring-size);
      height: var(--ring-size);
    }
    .ring {
      width: 100%;
      height: 100%;
    }
    .ring-track,
    .ring-arc {
      fill: none;
      stroke-width: 5;
    }
    .ring-track {
      stroke: var(--surface-sunken);
    }
    .ring-center {
      position: absolute;
      inset: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--space-1);
    }
    .ring-total {
      font-size: var(--font-size-2xl);
      font-weight: var(--font-weight-bold);
      line-height: 1;
      letter-spacing: -0.02em;
      color: var(--text-primary);
    }
    .ring-caption {
      font-size: var(--font-size-2xs);
      font-weight: var(--font-weight-medium);
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--text-muted);
    }

    .legend {
      margin: 0;
      padding: 0;
      list-style: none;
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .legend li {
      display: grid;
      grid-template-columns: auto 1fr auto auto;
      align-items: center;
      gap: var(--space-2);
      font-size: var(--font-size-body);
      color: var(--text-secondary);
    }
    .dot {
      width: var(--space-2);
      height: var(--space-2);
      border-radius: var(--radius-pill);
    }
    .name {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .count {
      font-weight: var(--font-weight-bold);
      color: var(--text-primary);
    }
    .share {
      min-width: 3.5ch;
      text-align: right;
      color: var(--text-muted);
    }

    .tone-ok {
      background: var(--status-ok);
    }
    .tone-warning {
      background: var(--status-warning);
    }
    .tone-risk {
      background: var(--status-risk);
    }
    .tone-danger {
      background: var(--status-danger);
    }
    .tone-info {
      background: var(--status-info);
    }
    .tone-neutral {
      background: var(--text-tertiary);
    }
    /* El SVG pinta con stroke, no con background: los mismos tonos, otra propiedad. */
    .ring-arc {
      background: none;
    }
    .ring-arc.tone-ok {
      stroke: var(--status-ok);
    }
    .ring-arc.tone-warning {
      stroke: var(--status-warning);
    }
    .ring-arc.tone-risk {
      stroke: var(--status-risk);
    }
    .ring-arc.tone-danger {
      stroke: var(--status-danger);
    }
    .ring-arc.tone-info {
      stroke: var(--status-info);
    }
    .ring-arc.tone-neutral {
      stroke: var(--text-primary);
    }
  `,
  imports: [RatePipe],
})
export class DistributionComponent {
  readonly items = input.required<DistributionItem[]>();
  readonly shape = input<DistributionShape>('bar');
  /** Debajo del número del centro del anillo ("expedientes"). Vacío deja sólo el número. */
  readonly centerLabel = input('');
  /**
   * For categories that overlap (one case in two buckets): the population each share is read
   * against. With it neither the stacked bar nor the ring is drawn — overlapping slices can't add
   * up to one whole — and the legend's percentages don't add up to 100%, which is the honest
   * reading.
   */
  readonly total = input<number | null>(null);

  protected readonly sum = computed(() => this.items().reduce((sum, item) => sum + item.count, 0));

  protected readonly slices = computed(() => {
    const total = this.total() ?? this.sum();
    const seen = new Map<StatusTone, number>();
    let offset = 0;
    return this.items().map((item) => {
      const repeat = seen.get(item.tone) ?? 0;
      seen.set(item.tone, repeat + 1);
      const share = total === 0 ? 0 : item.count / total;
      const slice = {
        ...item,
        share,
        offset,
        faded: repeat > 0,
        // Rampa de intensidad para las categorías que comparten tono — en el anillo son TODAS
        // cuando la dimensión no comunica estado (un hecho generador no es bueno ni malo, así que
        // el dominio las manda en neutro). Un solo color en distintas intensidades es lo que deja
        // distinguir las porciones sin inventar una paleta que el sistema no tiene y sin pintar de
        // rojo o verde algo que no es un semáforo.
        weight: Math.max(1 - repeat * 0.22, 0.25),
      };
      offset += share;
      return slice;
    });
  });

  /** Lo que el dibujo dice, en palabras, para quien no lo ve. */
  protected readonly summary = computed(() =>
    this.items()
      .map((item) => `${item.label}: ${item.count}`)
      .join('. '),
  );
}
