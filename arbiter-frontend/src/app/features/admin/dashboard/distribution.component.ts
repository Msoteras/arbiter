import { PercentPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { StatusTone } from '../../../core/models/status-tone';

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
 */
@Component({
  selector: 'app-distribution',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="dist">
      <div
        class="stack"
        role="img"
        [attr.aria-label]="summary()"
      >
        @for (slice of slices(); track slice.label) {
          <span
            class="slice tone-{{ slice.tone }}"
            [style.width.%]="slice.share * 100"
            [class.faded]="slice.faded"
          ></span>
        }
      </div>

      <ul class="legend">
        @for (slice of slices(); track slice.label) {
          <li>
            <span class="dot tone-{{ slice.tone }}" [class.faded]="slice.faded" aria-hidden="true"></span>
            <span class="name">{{ slice.label }}</span>
            <span class="count tabular">{{ slice.count }}</span>
            <span class="share tabular">{{ slice.share | percent: '1.0-0' }}</span>
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
    .slice.faded,
    .dot.faded {
      opacity: 0.5;
    }
    .legend {
      margin: var(--space-4) 0 0;
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
  imports: [PercentPipe],
})
export class DistributionComponent {
  readonly items = input.required<DistributionItem[]>();

  private readonly total = computed(() =>
    this.items().reduce((sum, item) => sum + item.count, 0),
  );

  protected readonly slices = computed(() => {
    const total = this.total();
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
