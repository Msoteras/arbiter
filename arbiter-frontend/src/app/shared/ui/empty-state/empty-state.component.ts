import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Placeholder honesto para secciones cuyos datos el backend todavía no expone. */
@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty">
      <p class="msg">{{ message() }}</p>
      <p class="sub">Sin datos</p>
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
}
