import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { StatusTone } from '../../../core/models/status-tone';
import { RatePipe } from '../../pipes/rate.pipe';

/** Una categoría de la distribución, con su color del semáforo ya resuelto por el dominio. */
export interface DistributionItem {
  label: string;
  count: number;
  tone: StatusTone;
}

/**
 * Una distribución como barra apilada más su lista.
 *
 * Reemplaza al anillo que había antes, y no por gusto: con cuatro o cinco categorías y una tarjeta
 * angosta, el anillo obliga a comparar arcos y a saltar a la leyenda para saber cuál es cuál. La
 * barra da la proporción de un vistazo y la lista da el número exacto al lado del nombre, que es
 * lo que el referente termina leyendo.
 *
 * Sin librería de gráficos: son divs con un ancho porcentual. Un canvas acá sería más código,
 * más peso y menos accesible.
 *
 * Vive en el kit y no en una feature porque lo usan dos: el tablero del referente y el reporte de
 * resolución. La leyenda es también lo que evita el problema que tenía el reporte cuando la
 * maquetaba a mano — con el nombre y el número en extremos opuestos de una fila ancha, el ojo no
 * los asocia.
 */
@Component({
  selector: 'app-distribution',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="dist">
      @if (total() === null) {
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

      <ul class="legend">
        @for (slice of slices(); track slice.label) {
          <li>
            <span
              class="dot tone-{{ slice.tone }}"
              [class.faded]="slice.faded"
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
  `,
  imports: [RatePipe],
})
export class DistributionComponent {
  readonly items = input.required<DistributionItem[]>();
  /**
   * For categories that overlap (one case in two buckets): the population each share is read
   * against. With it the stacked bar is not drawn — overlapping slices can't add up to one bar —
   * and the legend's percentages don't add up to 100%, which is the honest reading.
   */
  readonly total = input<number | null>(null);

  private readonly sum = computed(() => this.items().reduce((sum, item) => sum + item.count, 0));

  protected readonly slices = computed(() => {
    const total = this.total() ?? this.sum();
    const seen = new Map<StatusTone, number>();
    return this.items().map((item) => {
      const repeat = seen.get(item.tone) ?? 0;
      seen.set(item.tone, repeat + 1);
      return {
        ...item,
        share: total === 0 ? 0 : item.count / total,
        faded: repeat > 0,
      };
    });
  });

  /** Lo que la barra dice, en palabras, para quien no la ve. */
  protected readonly summary = computed(() =>
    this.items()
      .map((item) => `${item.label}: ${item.count}`)
      .join('. '),
  );
}
