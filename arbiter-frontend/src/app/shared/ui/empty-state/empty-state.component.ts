import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Nothing to show. Content projected into it is the way out (a button: "Limpiar filtros",
 * "Cargar el primero"); it sits under the text.
 */
@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty">
      <svg
        class="icon"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        aria-hidden="true"
      >
        <path d="M4 13h4l1.5 2.5h5L16 13h4" stroke-linejoin="round" />
        <path
          d="M6.2 6.6 4 13v5a1.5 1.5 0 0 0 1.5 1.5h13A1.5 1.5 0 0 0 20 18v-5l-2.2-6.4A1.5 1.5 0 0 0 16.4 5.5H7.6a1.5 1.5 0 0 0-1.4 1.1z"
          stroke-linejoin="round"
        />
      </svg>
      <p class="msg">{{ message() }}</p>
      @if (sub()) {
        <p class="sub">{{ sub() }}</p>
      }
      <div class="action">
        <ng-content />
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      border: 1px dashed var(--border-strong);
      border-radius: var(--radius-card);
      background: var(--surface-sunken);
      padding: var(--space-5) var(--space-4);
      text-align: center;
    }
    .icon {
      width: var(--icon-btn-sm);
      height: var(--icon-btn-sm);
      margin-bottom: var(--space-2);
      color: var(--text-muted);
    }
    .msg {
      margin: 0 0 var(--space-1);
      font-size: var(--font-size-body);
      color: var(--text-tertiary);
    }
    .sub {
      margin: 0;
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      text-wrap: pretty;
    }
    .action:not(:empty) {
      margin-top: var(--space-3);
    }
  `,
})
export class EmptyStateComponent {
  readonly message = input('Sin datos');
  /** Line under the message, usually a sentence ("Probá con otro rango de fechas"), so it is not
   *  set in capitals. Empty by default: repeating the message adds nothing. */
  readonly sub = input('');
}
