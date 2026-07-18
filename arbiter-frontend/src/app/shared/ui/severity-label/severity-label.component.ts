import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

type Severity = 'bajo' | 'medio' | 'alto';

/**
 * Etiqueta de severidad del design system. Diferencia por PESO tipográfico + ícono
 * triangular, nunca por color (regla de diseño de Arbiter).
 */
@Component({
  selector: 'app-severity-label',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="sev" [class.medio]="level() === 'medio'" [class.alto]="level() === 'alto'">
      @if (level() !== 'bajo') { <span class="tri" aria-hidden="true">▲</span> }
      {{ text() }}
    </span>
  `,
  styles: `
    .sev { font-size: 11px; color: var(--text-muted); display: inline-flex; gap: 3px; align-items: center; }
    .sev.medio { color: var(--text-tertiary); font-weight: 600; }
    .sev.alto { color: var(--text-primary); font-weight: 700; }
    .tri { font-size: 9px; }
  `,
})
export class SeverityLabelComponent {
  readonly level = input.required<Severity>();
  protected readonly text = computed(
    () => ({ bajo: 'Bajo', medio: 'Medio', alto: 'Alto' })[this.level()],
  );
}
