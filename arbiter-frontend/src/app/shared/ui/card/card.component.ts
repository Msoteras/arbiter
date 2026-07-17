import { ChangeDetectionStrategy, Component, input } from '@angular/core';

type Variant = 'default' | 'soft';

/**
 * Contenedor con borde + radio. `soft` (fondo tenue) se usa para las tarjetas de IA.
 * Cabecera opcional vía `heading` (+ `icon` opcional, ej. '✦' para sugerencias del modelo).
 * El cuerpo va como contenido proyectado.
 */
@Component({
  selector: 'app-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card" [class.soft]="variant() === 'soft'">
      @if (heading()) {
        <div class="card-head">
          @if (icon()) { <span class="icon" aria-hidden="true">{{ icon() }}</span> }
          <h2 class="card-title">{{ heading() }}</h2>
        </div>
      }
      <ng-content />
    </section>
  `,
  styles: `
    :host { display: block; }
    .card {
      border: 1px solid var(--border-control);
      border-radius: var(--radius-card);
      background: var(--surface);
      padding: var(--space-4);
    }
    .card.soft { background: var(--surface-soft); }
    .card-head { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-3); }
    .icon { color: var(--text-primary); }
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
}
