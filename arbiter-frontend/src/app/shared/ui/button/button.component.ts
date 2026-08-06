import { ChangeDetectionStrategy, Component, input } from '@angular/core';

type Variant = 'primary' | 'secondary' | 'accent';
type Size = 'md' | 'sm';

/**
 * Botón del design system. Única definición de "qué es un botón" en la app.
 * Variantes cerradas por la API (primary | secondary) → imposible inventar uno nuevo.
 * El click nativo burbujea al host, así que (click) sobre <app-button> funciona.
 */
@Component({
  selector: 'app-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.block]': 'block()' },
  template: `
    <button
      class="btn"
      [class.primary]="variant() === 'primary'"
      [class.secondary]="variant() === 'secondary'"
      [class.accent]="variant() === 'accent'"
      [class.sm]="size() === 'sm'"
      [class.loading]="loading()"
      [type]="type()"
      [disabled]="disabled() || loading()"
    >
      @if (loading()) { <span class="btn-spinner" aria-hidden="true"></span> }
      <ng-content />
    </button>
  `,
  styles: `
    :host { display: inline-block; }
    :host(.block) { display: block; }
    :host(.block) .btn { width: 100%; }
    .btn {
      font: inherit;
      font-size: var(--font-size-body);
      cursor: pointer;
      padding: var(--space-2) var(--space-4);
      border-radius: var(--radius-ctl);
      border: 1px solid transparent;
      white-space: nowrap;
    }
    .btn.loading { display: inline-flex; align-items: center; justify-content: center; gap: var(--space-2); }
    .btn-spinner {
      width: 0.9em;
      height: 0.9em;
      border: 2px solid currentColor;
      border-right-color: transparent;
      border-radius: var(--radius-pill);
      animation: btn-spin 0.6s linear infinite;
      flex-shrink: 0;
    }
    @keyframes btn-spin { to { transform: rotate(360deg); } }
    @media (prefers-reduced-motion: reduce) { .btn-spinner { animation-duration: 1.5s; } }
    .btn.sm { font-size: var(--font-size-sm); padding: var(--space-1) var(--space-3); }
    .btn.primary { background: var(--action-primary-bg); border-color: var(--action-primary-bg); color: var(--action-primary-fg); }
    .btn.primary:hover:not(:disabled) { background: var(--action-primary-bg-hover); border-color: var(--action-primary-bg-hover); }
    .btn.secondary { background: var(--action-secondary-bg); border-color: var(--action-secondary-border); color: var(--action-secondary-fg); }
    .btn.secondary:hover:not(:disabled) { border-color: var(--action-secondary-border-hover); }
    .btn.accent { background: var(--action-accent-bg); border-color: var(--action-accent-bg); color: var(--action-accent-fg); }
    .btn.accent:hover:not(:disabled) { background: var(--action-accent-bg-hover); border-color: var(--action-accent-bg-hover); }
    .btn:disabled { color: var(--text-muted); background: var(--surface-sunken); border-color: var(--border-subtle); cursor: default; pointer-events: none; }
  `,
})
export class ButtonComponent {
  readonly variant = input<Variant>('primary');
  readonly size = input<Size>('md');
  readonly type = input<'button' | 'submit'>('button');
  readonly disabled = input(false);
  readonly block = input(false);
  /** Muestra un spinner inline y deshabilita el botón mientras dura una acción async. */
  readonly loading = input(false);
}
