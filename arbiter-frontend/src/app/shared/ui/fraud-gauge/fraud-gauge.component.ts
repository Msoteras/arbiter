import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { StatusTone } from '../../../core/models/status-tone';

type Band = 1 | 2 | 3 | 4 | null;

/**
 * Gauge de fraude reutilizable (design system). 4 segmentos (30/30/20/20).
 * Categórico, sin número. band=null → estado "sin datos" (todos los segmentos apagados).
 */
@Component({
  selector: 'app-fraud-gauge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-tone]': "tone() !== 'neutral' ? tone() : null" },
  template: `
    <div class="gauge" role="img" [attr.aria-label]="'Riesgo de fraude: ' + label()">
      @for (seg of segments; track seg) {
        <span
          class="seg"
          [class.filled]="band() !== null && seg <= band()!"
          [class.active]="seg === band()"
          [style.flex-basis.%]="widths[seg - 1]"
        ></span>
      }
    </div>
    <div class="gauge-label">
      @if (band() !== null) { <span class="tri" aria-hidden="true">▲</span> }
      <span [class.muted]="band() === null">{{ label() }}</span>
    </div>
  `,
  styles: `
    :host { display: block; }
    .gauge { display: flex; gap: 3px; height: 10px; }
    .seg { display: block; background: var(--border-subtle); border-radius: 2px; }
    .seg.filled { background: var(--border-strong); }
    .seg.active { outline: 2px solid var(--text-primary); outline-offset: -1px; }
    .gauge-label { margin-top: 6px; font-size: var(--font-size-body); font-weight: var(--font-weight-bold); display: flex; gap: 5px; align-items: center; }
    .gauge-label .muted { font-weight: var(--font-weight-regular); color: var(--text-muted); }
    .tri { font-size: var(--font-size-xs); }

    /* Semáforo de riesgo: bajo→ok, medio→warning, alto/crítico→danger. */
    :host([data-tone='ok']) .seg.filled { background: var(--status-ok); }
    :host([data-tone='warning']) .seg.filled { background: var(--status-warning); }
    :host([data-tone='danger']) .seg.filled { background: var(--status-danger); }
    :host([data-tone='ok']) .tri { color: var(--status-ok); }
    :host([data-tone='warning']) .tri { color: var(--status-warning); }
    :host([data-tone='danger']) .tri { color: var(--status-danger); }
  `,
})
export class FraudGaugeComponent {
  readonly band = input<Band>(null);
  protected readonly segments = [1, 2, 3, 4] as const;
  protected readonly widths = [30, 30, 20, 20];
  protected readonly label = computed(() => {
    const labels: Record<number, string> = { 1: 'Bajo', 2: 'Medio', 3: 'Alto', 4: 'Crítico' };
    const b = this.band();
    return b === null ? 'Sin datos' : labels[b];
  });

  /** Nivel de riesgo → tono de semáforo. Alto y Crítico comparten rojo (los distingue el fill + el ▲). */
  protected readonly tone = computed<StatusTone>(() => {
    const b = this.band();
    if (b === null) return 'neutral';
    if (b === 1) return 'ok';
    if (b === 2) return 'warning';
    return 'danger';
  });
}
