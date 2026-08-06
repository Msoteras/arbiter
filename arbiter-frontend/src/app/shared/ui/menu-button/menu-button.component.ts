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
  /** Acción destructiva (ej. "Liberar"): se separa del resto y se pinta con el color de peligro. */
  danger?: boolean;
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
      <app-button variant="secondary" [size]="size()" [disabled]="disabled()" (click)="toggle()">
        <ng-content />
      </app-button>

      @if (open()) {
        <ul class="panel" role="menu">
          @if (heading(); as h) {
            <li role="presentation" class="heading">{{ h }}</li>
          }
          @for (item of items(); track item.value) {
            <li role="none" [class.danger-sep]="item.danger">
              <button
                type="button"
                role="menuitem"
                class="option"
                [class.danger]="item.danger"
                (click)="choose(item)"
              >
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

    .heading {
      padding: var(--space-1) var(--space-3) var(--space-2);
      font-size: var(--font-size-2xs);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--text-muted);
    }
    .danger-sep { border-top: 1px solid var(--border-subtle); margin-top: var(--space-1); padding-top: var(--space-1); }
    .option.danger { color: var(--status-danger); }
    .option.danger:hover { background: var(--surface-sunken); color: var(--status-danger); }
  `,
})
export class MenuButtonComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly items = input.required<MenuItem[]>();
  /** Título opcional arriba de la lista (ej. "Asignar a otro analista"). No es clickeable. */
  readonly heading = input<string | null>(null);
  readonly disabled = input(false);
  /** Mismo tamaño que app-button: `sm` para triggers que viven dentro de una fila de tabla. */
  readonly size = input<'md' | 'sm'>('md');
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
