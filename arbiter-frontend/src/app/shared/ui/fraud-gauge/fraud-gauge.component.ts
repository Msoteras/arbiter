import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

type Band = 1 | 2 | 3 | 4 | null;

/**
 * Gauge de fraude reutilizable (design system). 4 segmentos (30/30/20/20).
 * Categórico, sin número. band=null → estado "sin datos" (todos los segmentos apagados).
 */
@Component({
  selector: 'app-fraud-gauge',
  changeDetection: ChangeDetectionStrategy.OnPush,
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
    .seg { display: block; background: var(--c-divider); border-radius: 2px; }
    .seg.filled { background: var(--c-border-strong); }
    .seg.active { outline: 2px solid var(--c-ink); outline-offset: -1px; }
    .gauge-label { margin-top: 6px; font-size: 13px; font-weight: 700; display: flex; gap: 5px; align-items: center; }
    .gauge-label .muted { font-weight: 400; color: var(--c-muted); }
    .tri { font-size: 11px; }
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
}
