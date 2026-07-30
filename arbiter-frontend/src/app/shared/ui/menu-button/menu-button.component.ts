import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ButtonComponent } from '../button/button.component';

export interface MenuItem {
  value: string;
  label: string;
}

/**
 * Botón con menú desplegable (ej. "Exportar" → CSV / XLSX). Mismo trigger que app-button
 * + panel de opciones con el tratamiento visual de app-select (superficie/bordes/radios
 * del sistema). El label del trigger se proyecta (permite el ícono + texto que ya usan
 * los botones con `.btn-with-icon`); las opciones se declaran por datos, no por proyección,
 * para que el panel salga siempre con el mismo look sin que cada consumidor lo reimplemente.
 */
@Component({
  selector: 'app-menu-button',
  imports: [ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="menu" [class.open]="open()" [class.align-end]="align() === 'end'">
      <app-button variant="secondary" [disabled]="disabled()" (click)="toggle()">
        <ng-content />
      </app-button>

      @if (open()) {
        <ul class="panel" role="menu">
          @for (item of items(); track item.value) {
            <li role="none">
              <button type="button" role="menuitem" class="option" (click)="choose(item)">
                {{ item.label }}
              </button>
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: `
    :host { display: inline-block; }
    .menu { position: relative; }

    .panel {
      position: absolute;
      top: calc(100% + var(--space-1));
      left: 0;
      z-index: 50;
      margin: 0;
      padding: var(--space-1);
      list-style: none;
      min-width: 100%;
      width: max-content;
      background: var(--surface);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      box-shadow: var(--shadow-modal);
    }
    .menu.align-end .panel { left: auto; right: 0; }

    .option {
      display: block;
      width: 100%;
      font: inherit;
      font-size: var(--font-size-body);
      text-align: left;
      padding: var(--space-2) var(--space-3);
      border: none;
      border-radius: var(--radius-ctl);
      background: none;
      color: var(--text-secondary);
      cursor: pointer;
      white-space: nowrap;
    }
    .option:hover { background: var(--surface-sunken); color: var(--text-primary); }
  `,
})
export class MenuButtonComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly items = input.required<MenuItem[]>();
  readonly disabled = input(false);
  /** Lado del trigger al que se ancla el panel — "end" para triggers pegados al borde derecho. */
  readonly align = input<'start' | 'end'>('start');

  readonly itemSelected = output<string>();

  protected readonly open = signal(false);

  protected toggle(): void {
    if (this.disabled()) return;
    this.open.update((o) => !o);
  }

  protected choose(item: MenuItem): void {
    this.open.set(false);
    this.itemSelected.emit(item.value);
  }

  @HostListener('document:click', ['$event'])
  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    this.open.set(false);
  }
}
