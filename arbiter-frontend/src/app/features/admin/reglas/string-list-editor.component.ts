import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';

/**
 * Editor de una lista de textos (reglas, exclusiones, criterios de fast track): agrega, edita
 * y quita items. Emite la lista completa en cada cambio; el padre la persiste en su draft.
 * Local al feature de reglas — no es una primitiva del kit.
 */
@Component({
  selector: 'app-string-list-editor',
  imports: [ButtonComponent, InputComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul class="items">
      @for (item of items(); track $index) {
        <li class="item">
          <app-input
            class="grow"
            [value]="item"
            (valueChange)="update($index, $event)"
            [placeholder]="placeholder()"
          />
          <button type="button" class="remove" (click)="remove($index)" [attr.aria-label]="'Quitar'">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
              <line x1="18" y1="6" x2="6" y2="18" stroke-linecap="round" />
              <line x1="6" y1="6" x2="18" y2="18" stroke-linecap="round" />
            </svg>
          </button>
        </li>
      } @empty {
        <li class="empty t-note">{{ emptyText() }}</li>
      }
    </ul>
    <app-button variant="secondary" size="sm" (click)="add()">{{ addLabel() }}</app-button>
  `,
  styles: `
    :host { display: block; }
    .items { list-style: none; margin: 0 0 var(--space-2); padding: 0; display: flex; flex-direction: column; gap: var(--space-2); }
    .item { display: flex; align-items: center; gap: var(--space-2); }
    .grow { flex: 1 1 auto; }
    .empty { margin: 0 0 var(--space-1); }
    .remove {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 34px;
      height: 34px;
      padding: 0;
      background: none;
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      color: var(--text-tertiary);
      cursor: pointer;
    }
    .remove:hover { border-color: var(--status-danger); color: var(--status-danger); }
    .remove svg { width: 16px; height: 16px; }
  `,
})
export class StringListEditorComponent {
  readonly items = input<string[]>([]);
  readonly placeholder = input('');
  readonly addLabel = input('+ Agregar');
  readonly emptyText = input('Sin items configurados.');

  readonly itemsChange = output<string[]>();

  protected update(index: number, value: string): void {
    const next = [...this.items()];
    next[index] = value;
    this.itemsChange.emit(next);
  }

  protected remove(index: number): void {
    this.itemsChange.emit(this.items().filter((_, i) => i !== index));
  }

  protected add(): void {
    this.itemsChange.emit([...this.items(), '']);
  }
}
