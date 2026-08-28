import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  Injectable,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ButtonComponent } from '../button/button.component';
import { OverlayPosition, anchorToTrigger } from '../overlay-position';

export interface MenuItem {
  value: string;
  label: string;
  /** Acción destructiva (ej. "Liberar"): se separa del resto y se pinta con el color de peligro. */
  danger?: boolean;
}

interface ClosableMenu {
  closeFromRegistry(): void;
}

/**
 * Garantiza que haya un solo menú abierto a la vez. Coordinar por acá (y no por el `document:click`
 * de cada menú) es necesario porque ese click puede venir con `stopPropagation` — la celda de la
 * bandeja lo hace para no navegar a la fila — y ahí un menú nunca se entera de que se abrió otro.
 */
@Injectable({ providedIn: 'root' })
export class MenuButtonRegistry {
  private current: ClosableMenu | null = null;

  open(menu: ClosableMenu): void {
    if (this.current && this.current !== menu) {
      this.current.closeFromRegistry();
    }
    this.current = menu;
  }

  close(menu: ClosableMenu): void {
    if (this.current === menu) {
      this.current = null;
    }
  }
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
  host: { '[class.block]': 'block()' },
  template: `
    <div class="menu" [class.open]="open()">
      <app-button
        variant="secondary"
        [size]="size()"
        [block]="block()"
        [disabled]="disabled()"
        (click)="toggle()"
      >
        <ng-content />
      </app-button>

      @if (open()) {
        <ul
          class="panel"
          role="menu"
          [style.top.px]="panelPos().top"
          [style.bottom.px]="panelPos().bottom"
          [style.left.px]="panelPos().left"
          [style.right.px]="panelPos().right"
        >
          @if (heading(); as h) {
            <li role="presentation" class="heading">{{ h }}</li>
          }
          @for (item of items(); track item.value; let i = $index) {
            <li role="none" [class.danger-sep]="item.danger && i > 0">
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
    :host(.block) { display: block; }
    :host(.block) .menu { width: 100%; }
    .menu { position: relative; }

    /* fixed, no absolute: anclado al trigger por coordenadas de viewport. Como absolute lo
       recortaba cualquier ancestro con overflow (ej. el wrapper con scroll horizontal de la
       bandeja, que la spec obliga a recortar también en vertical). */
    .panel {
      position: fixed;
      z-index: 50;
      margin: 0;
      padding: var(--space-1);
      list-style: none;
      width: max-content;
      background: var(--surface);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      box-shadow: var(--shadow-modal);
    }

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
export class MenuButtonComponent implements ClosableMenu {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly registry = inject(MenuButtonRegistry);

  constructor() {
    const destroyRef = inject(DestroyRef);
    // Si la fila (y su menú) se destruye estando abierto, no dejar la referencia colgada.
    destroyRef.onDestroy(() => this.registry.close(this));

    // El panel es `fixed`: no acompaña a lo que scrollea, así que se cierra en vez de quedar
    // flotando lejos del trigger. En captura para enterarse del scroll de cualquier contenedor
    // interno (ej. la tabla de la bandeja), que no burbujea a window.
    const onScroll = () => {
      if (this.open()) this.close();
    };
    document.addEventListener('scroll', onScroll, true);
    destroyRef.onDestroy(() => document.removeEventListener('scroll', onScroll, true));
  }

  readonly items = input.required<MenuItem[]>();
  /** Título opcional arriba de la lista (ej. "Asignar a otro analista"). No es clickeable. */
  readonly heading = input<string | null>(null);
  readonly disabled = input(false);
  /** Trigger a ancho completo (para menús que actúan como botón principal, ej. "Reasignar"). */
  readonly block = input(false);
  /** Mismo tamaño que app-button: `sm` para triggers que viven dentro de una fila de tabla. */
  readonly size = input<'md' | 'sm'>('md');
  /** Lado del trigger al que se ancla el panel — "end" para triggers pegados al borde derecho. */
  readonly align = input<'start' | 'end'>('start');

  readonly itemSelected = output<string>();

  protected readonly open = signal(false);
  /** Coordenadas de viewport del panel (es `fixed`). Null en el eje que no se fija. */
  protected readonly panelPos = signal<OverlayPosition>({
    top: null,
    bottom: null,
    left: null,
    right: null,
    width: null,
  });

  protected toggle(): void {
    if (this.disabled()) return;
    if (this.open()) {
      this.close();
    } else {
      // Se posiciona ANTES de renderizar para que aparezca directo en su lugar, sin parpadeo.
      this.positionPanel();
      this.open.set(true);
      // Cierra cualquier otro menú abierto (uno solo a la vez).
      this.registry.open(this);
    }
  }

  private close(): void {
    this.open.set(false);
    this.registry.close(this);
  }

  /** Lo llama el registry al abrirse otro menú: baja este sin volver a notificar (evita recursión). */
  closeFromRegistry(): void {
    this.open.set(false);
  }

  private positionPanel(): void {
    const estPanelHeight = (this.heading() ? 28 : 0) + this.items().length * 40 + 12;
    this.panelPos.set(
      anchorToTrigger(this.host.nativeElement.getBoundingClientRect(), estPanelHeight, this.align()),
    );
  }

  protected choose(item: MenuItem): void {
    this.close();
    this.itemSelected.emit(item.value);
  }

  @HostListener('document:click', ['$event'])
  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.open()) this.close();
  }

  @HostListener('window:resize')
  protected onViewportChange(): void {
    if (this.open()) this.close();
  }
}
