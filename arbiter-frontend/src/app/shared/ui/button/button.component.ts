import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { SpinnerComponent } from '../spinner/spinner.component';

type Variant = 'primary' | 'secondary' | 'accent';
type Size = 'md' | 'sm';

/**
 * Botón del design system. Única definición de "qué es un botón" en la app.
 * Variantes cerradas por la API (primary | secondary) → imposible inventar uno nuevo.
 * El click nativo burbujea al host, así que (click) sobre <app-button> funciona — y por eso mismo
 * el estado deshabilitado se corta acá (ver onHostClick).
 */
@Component({
  selector: 'app-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpinnerComponent],
  host: {
    '[class.block]': 'block()',
    '[class.is-disabled]': 'disabled() || loading()',
  },
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
      @if (loading()) {
        <app-spinner [size]="16" />
      }
      <ng-content />
    </button>
  `,
  styles: `
    :host {
      display: inline-block;
    }
    /* El (click) de los consumidores vive en el host y se dispara aunque el <button> interno esté
       disabled; un HostListener no gana, porque el del consumidor se registra primero. Sin eventos
       de puntero tampoco hay hover: el title de un botón deshabilitado va en algo que lo envuelva. */
    :host(.is-disabled) {
      pointer-events: none;
    }
    :host(.block) {
      display: block;
    }
    :host(.block) .btn {
      width: 100%;
    }
    .btn {
      font: inherit;
      font-size: var(--font-size-body);
      cursor: pointer;
      padding: var(--space-2) var(--space-4);
      border-radius: var(--radius-ctl);
      border: 1px solid transparent;
      white-space: nowrap;
    }
    .btn.loading {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: var(--space-2);
    }
    .btn.sm {
      font-size: var(--font-size-sm);
      padding: var(--space-1) var(--space-3);
    }
    .btn.primary {
      background: var(--action-primary-bg);
      border-color: var(--action-primary-bg);
      color: var(--action-primary-fg);
    }
    .btn.primary:hover:not(:disabled) {
      background: var(--action-primary-bg-hover);
      border-color: var(--action-primary-bg-hover);
    }
    .btn.secondary {
      background: var(--action-secondary-bg);
      border-color: var(--action-secondary-border);
      color: var(--action-secondary-fg);
    }
    .btn.secondary:hover:not(:disabled) {
      border-color: var(--action-secondary-border-hover);
    }
    .btn.accent {
      background: var(--action-accent-bg);
      border-color: var(--action-accent-bg);
      color: var(--action-accent-fg);
    }
    .btn.accent:hover:not(:disabled) {
      background: var(--action-accent-bg-hover);
      border-color: var(--action-accent-bg-hover);
    }
    .btn:disabled {
      color: var(--text-muted);
      background: var(--surface-sunken);
      border-color: var(--border-subtle);
      cursor: default;
      pointer-events: none;
    }
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
