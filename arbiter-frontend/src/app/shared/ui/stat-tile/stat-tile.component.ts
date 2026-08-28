import { ChangeDetectionStrategy, Component, effect, input, signal } from '@angular/core';

type Tone = 'default' | 'accent' | 'danger';

/**
 * Tarjeta de métrica de las pantallas de inicio: la etiqueta chica en mayúsculas arriba y el
 * número grande abajo, con una nota opcional (mismo layout que el prototipo hi-fi).
 *
 * `tone` tiñe la tarjeta y el número para el dato que comunica algo:
 *   accent → dato de marca a resaltar (ej. pendientes propios)
 *   danger → señal de alerta (ej. alertas de fraude, vencimientos)
 * Con `loading` muestra un guion mientras el conteo está en vuelo, en vez de un 0 que parpadea a
 * su valor real y se lee como "no hay nada".
 */
@Component({
  selector: 'app-stat-tile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stat" [class.accent]="tone() === 'accent'" [class.danger]="tone() === 'danger'">
      <span class="stat-label">{{ label() }}</span>
      <span class="stat-value tabular">{{ display() }}</span>
      @if (sub()) {
        <span class="stat-sub">{{ sub() }}</span>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .stat {
      display: flex;
      flex-direction: column;
      height: 100%;
      padding: var(--space-4);
      border: 1px solid var(--border-default);
      border-radius: var(--radius-card);
      background: var(--surface);
      box-shadow: var(--shadow-card);
    }
    /* Etiqueta arriba: chica, en mayúsculas con tracking, tenue. */
    .stat-label {
      font-size: var(--font-size-2xs);
      font-weight: var(--font-weight-medium);
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--text-muted);
    }
    /* Número grande debajo. */
    .stat-value {
      margin-top: var(--space-2);
      font-size: var(--font-size-2xl);
      font-weight: var(--font-weight-bold);
      line-height: 1;
      letter-spacing: -0.02em;
      color: var(--text-primary);
    }
    .stat-sub {
      margin-top: var(--space-1);
      font-size: var(--font-size-xs);
      color: var(--text-muted);
    }
    .stat.accent {
      background: var(--selected-bg);
      border-color: var(--selected-border);
    }
    .stat.accent .stat-value {
      color: var(--accent-fg);
    }
    .stat.danger {
      background: var(--surface-danger-soft);
      border-color: var(--border-danger-soft);
    }
    .stat.danger .stat-value {
      color: var(--status-danger);
    }
  `,
})
export class StatTileComponent {
  readonly label = input('');
  readonly value = input<string | number>('');
  readonly sub = input('');
  readonly tone = input<Tone>('default');
  readonly loading = input(false);

  /**
   * Valor que se pinta. Mientras carga es un guion; con un número, cuenta desde 0 hasta el valor
   * (una sola vez, al aparecer el dato) para dar el efecto "contador" de dashboard. Con
   * prefers-reduced-motion o valores no numéricos, se muestra el valor tal cual, sin animar.
   */
  protected readonly display = signal<string | number>('—');

  constructor() {
    effect((onCleanup) => {
      const v = this.value();
      if (this.loading()) {
        this.display.set('—');
        return;
      }
      if (typeof v !== 'number' || !Number.isFinite(v)) {
        this.display.set(v);
        return;
      }
      const reduce =
        typeof window !== 'undefined' &&
        window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
      if (reduce || v <= 0) {
        this.display.set(v);
        return;
      }
      const target = v;
      const duration = 650;
      let startTs: number | null = null;
      let raf = 0;
      const step = (ts: number) => {
        if (startTs === null) startTs = ts;
        const p = Math.min(1, (ts - startTs) / duration);
        const eased = 1 - Math.pow(1 - p, 3); // ease-out cubic
        this.display.set(Math.round(target * eased));
        if (p < 1) raf = requestAnimationFrame(step);
      };
      raf = requestAnimationFrame(step);
      onCleanup(() => cancelAnimationFrame(raf));
    });
  }
}
