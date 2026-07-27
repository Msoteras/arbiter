import { ChangeDetectionStrategy, Component, input } from '@angular/core';

type Variant = 'primary' | 'secondary';
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
      [class.sm]="size() === 'sm'"
      [type]="type()"
      [disabled]="disabled()"
    >
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
    .btn.sm { font-size: var(--font-size-sm); padding: var(--space-1) var(--space-3); }
    .btn.primary { background: var(--action-primary-bg); border-color: var(--action-primary-bg); color: var(--action-primary-fg); }
    .btn.primary:hover:not(:disabled) { background: var(--action-primary-bg-hover); border-color: var(--action-primary-bg-hover); }
    .btn.secondary { background: var(--action-secondary-bg); border-color: var(--action-secondary-border); color: var(--action-secondary-fg); }
    .btn.secondary:hover:not(:disabled) { border-color: var(--action-secondary-border-hover); }
    .btn:disabled { color: var(--text-muted); background: var(--surface-sunken); border-color: var(--border-subtle); cursor: default; pointer-events: none; }
  `,
})
export class ButtonComponent {
  readonly variant = input<Variant>('primary');
  readonly size = input<Size>('md');
  readonly type = input<'button' | 'submit'>('button');
  readonly disabled = input(false);
  readonly block = input(false);
}
