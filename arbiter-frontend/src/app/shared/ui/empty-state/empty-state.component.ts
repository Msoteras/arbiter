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
      border: 1px dashed var(--c-border-strong);
      border-radius: var(--radius-card);
      background: var(--c-bg-soft-2);
      padding: 26px 16px;
      text-align: center;
    }
    .msg { margin: 0 0 4px; font-size: 13px; color: var(--c-ink-3); }
    .sub { margin: 0; font-size: 11px; color: var(--c-muted); text-transform: uppercase; letter-spacing: 0.04em; }
  `,
})
export class EmptyStateComponent {
  readonly message = input('Sin datos');
}
