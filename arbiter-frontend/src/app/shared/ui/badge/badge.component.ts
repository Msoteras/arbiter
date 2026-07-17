import { ChangeDetectionStrategy, Component, input } from '@angular/core';

type Variant = 'solid' | 'dashed';

/**
 * Etiqueta compacta (pill). `solid` para estados con dato; `dashed` para
 * placeholders / secciones sin dato (coherente con el estilo "honesto" de Arbiter).
 */
@Component({
  selector: 'app-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="badge"
      [class.solid]="variant() === 'solid'"
      [class.dashed]="variant() === 'dashed'"
    >
      <ng-content />
    </span>
  `,
  styles: `
    .badge {
      display: inline-block;
      font-size: var(--font-size-xs);
      padding: var(--space-1) var(--space-3);
      border-radius: var(--radius-pill);
      line-height: 1.4;
    }
    .badge.solid { border: 1px solid var(--border-control); background: var(--surface-head); color: var(--text-secondary); }
    .badge.dashed { border: 1px dashed var(--border-strong); color: var(--text-muted); }
  `,
})
export class BadgeComponent {
  readonly variant = input<Variant>('solid');
}
