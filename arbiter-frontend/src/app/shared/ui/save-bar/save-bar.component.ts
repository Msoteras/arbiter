import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { ButtonComponent } from '../button/button.component';

/**
 * Footer of an editable section. Without `dirty` both buttons are disabled; `canSave` is a separate
 * validation flag so a disabled button means one thing at a time.
 */
@Component({
  selector: 'app-save-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonComponent],
  template: `
    <div class="save-bar">
      <span class="state" [class.dirty]="dirty()">
        @if (error()) {
          <span class="error">{{ error() }}</span>
        } @else if (dirty()) {
          Cambios sin guardar
        } @else {
          <span aria-hidden="true">✓</span> Todo guardado
        }
      </span>
      <div class="actions">
        <app-button variant="secondary" [disabled]="!dirty() || saving()" (click)="discard.emit()">
          Descartar
        </app-button>
        <app-button [disabled]="!dirty() || saving() || !canSave()" (click)="save.emit()">
          {{ saving() ? 'Guardando…' : saveLabel() }}
        </app-button>
      </div>
    </div>
  `,
  styles: `
    /* width:100%: inside a flex container the host would shrink to its content. */
    :host {
      display: block;
      width: 100%;
    }
    .save-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-4);
      flex-wrap: wrap;
      margin-top: var(--space-5);
      padding-top: var(--space-4);
      border-top: 1px solid var(--border-default);
    }
    .actions {
      display: flex;
      align-items: center;
      gap: var(--space-2);
    }
    /* Unsaved changes are grey, not red: they are intentional, not an error. */
    .state {
      font-size: var(--font-size-sm);
      color: var(--status-ok);
    }
    .state.dirty {
      color: var(--text-secondary);
    }
    .error {
      color: var(--status-danger);
    }
  `,
})
export class SaveBarComponent {
  /** Differs from the last state confirmed by the backend. */
  readonly dirty = input(false);
  readonly saving = input(false);
  /** Backend error from a failed save; replaces the state text while present. */
  readonly error = input<string | null>(null);
  readonly canSave = input(true);
  readonly saveLabel = input('Guardar cambios');

  readonly save = output<void>();
  readonly discard = output<void>();
}
