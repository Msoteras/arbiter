import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Placeholder honesto para secciones cuyos datos el backend todavía no expone. */
@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty">
      <p class="msg">{{ message() }}</p>
      <p class="sub">{{ sub() }}</p>
    </div>
  `,
  styles: `
    :host { display: block; }
    .empty {
      border: 1px dashed var(--border-strong);
      border-radius: var(--radius-card);
      background: var(--surface-sunken);
      padding: var(--space-5) var(--space-4);
      text-align: center;
    }
    .msg { margin: 0 0 var(--space-1); font-size: var(--font-size-body); color: var(--text-tertiary); }
    .sub { margin: 0; font-size: var(--font-size-xs); color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.04em; }
  `,
})
export class EmptyStateComponent {
  readonly message = input('Sin datos');
  /** Etiqueta corta de estado bajo el mensaje. Por defecto "Sin datos"; pasá algo más
   *  descriptivo (ej. "Pendiente de datos") cuando el motivo del vacío no sea obvio. */
  readonly sub = input('Sin datos');
}
