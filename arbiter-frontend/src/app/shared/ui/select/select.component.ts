import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  computed,
  inject,
  input,
  model,
  signal,
} from '@angular/core';

import { OverlayPosition, anchorToTrigger } from '../overlay-position';

export interface SelectOption {
  value: string;
  label: string;
}

/** Cuántas opciones se dibujan como máximo con el buscador abierto (ver `visibleOptions`). */
const SEARCH_RESULT_LIMIT = 100;

/** Sin acentos y en minúscula, para que "cordoba" encuentre "Córdoba". */
function normalize(text: string): string {
  return text
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase();
}

/**
 * Select del design system. Listbox custom (no `<select>` nativo: el panel nativo lo
 * dibuja el SO y no respeta los tokens). Mismo tratamiento visual que app-input en el
 * trigger; el panel usa superficie/bordes/radios del sistema.
 *
 * Valor two-way vía model() → `[(value)]` (o `[value]` + `(valueChange)` cuando el
 * consumidor necesita interceptar el cambio, ej. para resetear paginación).
 * `placeholder` es la opción vacía (ej. "Todos los estados") — omitila si el campo es obligatorio.
 *
 * `searchable` agrega un buscador arriba del listado, para catálogos que no se recorren a ojo
 * (ej. las ~900 localidades de Buenos Aires). Filtra ignorando acentos y mayúsculas.
 *
 * Teclado: Enter/Espacio/↓ abre · ↑/↓ navega · Enter elige · Esc/Tab/click afuera cierra.
 * Con `searchable`, al abrir el foco va al buscador y desde ahí se sigue navegando con ↑/↓.
 */
@Component({
  selector: 'app-select',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="select" [class.open]="open()">
      <button
        type="button"
        class="trigger"
        [id]="resolvedId()"
        role="combobox"
        aria-haspopup="listbox"
        [attr.aria-expanded]="open()"
        [attr.aria-controls]="open() ? resolvedId() + '-listbox' : null"
        [attr.aria-activedescendant]="open() ? resolvedId() + '-opt-' + activeIndex() : null"
        [disabled]="disabled()"
        (click)="toggle()"
        (keydown)="onKeydown($event)"
      >
        <span class="trigger-label" [class.is-placeholder]="!selectedLabel()">
          {{ selectedLabel() || placeholder() }}
        </span>
        <svg class="chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
          <polyline points="6 9 12 15 18 9" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>

      @if (open()) {
        <div
          class="panel"
          [style.top.px]="panelPos().top"
          [style.bottom.px]="panelPos().bottom"
          [style.left.px]="panelPos().left"
          [style.width.px]="panelPos().width"
        >
          @if (searchable()) {
            <input
              type="text"
              class="search"
              role="searchbox"
              autocomplete="off"
              [attr.aria-label]="searchLabel()"
              [attr.aria-controls]="resolvedId() + '-listbox'"
              [attr.aria-activedescendant]="resolvedId() + '-opt-' + activeIndex()"
              [placeholder]="searchLabel()"
              [value]="query()"
              (input)="onQuery($event)"
              (keydown)="onKeydown($event)"
            />
          }

          <ul class="list" role="listbox" [id]="resolvedId() + '-listbox'">
            @for (opt of visibleOptions(); track opt.value; let i = $index) {
              <li
                class="option"
                role="option"
                [id]="resolvedId() + '-opt-' + i"
                [class.active]="activeIndex() === i"
                [class.is-placeholder]="opt.value === ''"
                [attr.aria-selected]="value() === opt.value"
                (click)="select(opt)"
                (mousemove)="activeIndex.set(i)"
              >
                {{ opt.label }}
              </li>
            } @empty {
              <li class="hint" role="presentation">Sin resultados</li>
            }
            <!-- Con catálogos largos se dibujan solo los primeros resultados: 900 <li> por cada
                 tecla del buscador se sentía. Escribir un poco más los recorta de verdad. -->
            @if (hiddenCount() > 0) {
              <li class="hint" role="presentation">
                +{{ hiddenCount() }} más — seguí escribiendo para achicar la lista
              </li>
            }
          </ul>
        </div>
      }
    </div>
  `,
  styles: `
    :host { display: block; }
    .select { position: relative; }

    .trigger {
      width: 100%;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
      font: inherit;
      font-size: var(--font-size-lg);
      text-align: left;
      padding: var(--space-2) var(--space-3);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: var(--surface);
      color: var(--text-primary);
      cursor: pointer;
    }
    .trigger:focus-visible,
    .select.open .trigger { outline: none; border-color: var(--border-focus); box-shadow: var(--focus-ring); }
    .trigger:disabled { cursor: default; opacity: 0.55; }
    .trigger-label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .trigger-label.is-placeholder { color: var(--text-muted); }
    .chevron {
      width: 1em;
      height: 1em;
      flex-shrink: 0;
      color: var(--text-tertiary);
      transition: transform 0.12s;
    }
    .select.open .chevron { transform: rotate(180deg); }

    /* fixed, no absolute: absolute lo recortaba cualquier ancestro con overflow (ej. la tabla
       de usuarios, cuyo scroll horizontal obliga a recortar también en vertical). */
    .panel {
      position: fixed;
      z-index: 50;
      display: flex;
      flex-direction: column;
      padding: var(--space-1);
      background: var(--surface);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      box-shadow: var(--shadow-modal);
      max-height: 40vh;
    }

    /* El buscador queda fijo arriba y solo scrollea el listado: si scrolleara con las opciones,
       al bajar por 900 localidades se perdía de vista lo que se estaba tipeando. */
    .search {
      flex-shrink: 0;
      width: 100%;
      font: inherit;
      font-size: var(--font-size-lg);
      padding: var(--space-2) var(--space-3);
      margin-bottom: var(--space-1);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: var(--surface-soft);
      color: var(--text-primary);
    }
    .search::placeholder { color: var(--text-muted); }
    .search:focus-visible { outline: none; border-color: var(--border-focus); box-shadow: var(--focus-ring); }

    .list {
      margin: 0;
      padding: 0;
      list-style: none;
      overflow-y: auto;
    }

    .hint {
      padding: var(--space-2) var(--space-3);
      font-size: var(--font-size-sm);
      color: var(--text-muted);
    }
    .option {
      padding: var(--space-2) var(--space-3);
      border-radius: var(--radius-ctl);
      font-size: var(--font-size-lg);
      color: var(--text-secondary);
      cursor: pointer;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .option.active { background: var(--surface-sunken); color: var(--text-primary); }
    /* La elegida se marca con la superficie de cabecera + peso, como el link activo de la nav. */
    .option[aria-selected='true'] { background: var(--surface-head); color: var(--text-primary); font-weight: var(--font-weight-medium); }
    .option.is-placeholder { color: var(--text-muted); }

    /* 16px en mobile (evita el zoom de iOS); tamaño de cuerpo en desktop. */
    @media (min-width: 640px) {
      .trigger, .option { font-size: var(--font-size-body); }
    }
  `,
})
export class SelectComponent {
  private static nextId = 0;

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly autoId = 'app-select-' + SelectComponent.nextId++;

  readonly value = model('');
  readonly options = input<SelectOption[]>([]);
  readonly placeholder = input('');
  /** Bloquea la apertura (ej. mientras se guarda el cambio contra el backend). */
  readonly disabled = input(false);
  /** Para asociar un `<label for>` — sin esto el trigger nunca tenía id propio, solo sus
   * sub-elementos internos (listbox/opciones). */
  readonly id = input<string | null>(null);
  /** Agrega el buscador arriba del listado. Para catálogos que no se recorren a ojo. */
  readonly searchable = input(false);
  /** Rótulo del buscador (placeholder + aria-label), ej. "Buscar localidad". */
  readonly searchPlaceholder = input('');

  protected readonly resolvedId = computed(() => this.id() ?? this.autoId);
  protected readonly searchLabel = computed(() => this.searchPlaceholder() || 'Buscar');

  protected readonly open = signal(false);
  protected readonly activeIndex = signal(0);
  protected readonly query = signal('');
  /** Coordenadas de viewport del panel (es `fixed`). Null en el eje que no se fija. */
  protected readonly panelPos = signal<OverlayPosition>({
    top: null,
    bottom: null,
    left: null,
    right: null,
    width: null,
  });

  constructor() {
    // El panel es `fixed`: no acompaña a lo que scrollea, así que se cierra cuando scrollea un
    // contenedor externo (se despegaría del trigger). En captura, para enterarse del scroll de
    // contenedores internos, que no burbujea a window.
    // PERO el propio panel tiene scroll interno (max-height 40vh): si el usuario está recorriendo
    // las opciones, ese scroll NO debe cerrarlo (si no, con muchas opciones no se puede bajar).
    const onScroll = (event: Event) => {
      if (!this.open()) return;
      const panel = this.host.nativeElement.querySelector('.panel');
      const target = event.target as Node | null;
      if (panel && target && panel.contains(target)) return; // scroll dentro del listado: no cerrar
      this.open.set(false);
    };
    document.addEventListener('scroll', onScroll, true);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('scroll', onScroll, true));
  }

  /** Opciones renderizadas: el placeholder es la opción vacía (permite "limpiar"). */
  protected readonly allOptions = computed<SelectOption[]>(() =>
    this.placeholder()
      ? [{ value: '', label: this.placeholder() }, ...this.options()]
      : this.options(),
  );

  /** Lo que sobrevive al buscador. Sin `searchable` (o sin texto tipeado) es `allOptions`. */
  protected readonly matchingOptions = computed<SelectOption[]>(() => {
    const q = normalize(this.query().trim());
    if (!this.searchable() || !q) {
      return this.allOptions();
    }
    // El placeholder se cae solo al filtrar: es la opción "vacía", no un resultado de búsqueda.
    return this.options().filter((o) => normalize(o.label).includes(q));
  });

  protected readonly visibleOptions = computed(() =>
    this.searchable() ? this.matchingOptions().slice(0, SEARCH_RESULT_LIMIT) : this.allOptions(),
  );

  protected readonly hiddenCount = computed(
    () => this.matchingOptions().length - this.visibleOptions().length,
  );

  protected readonly selectedLabel = computed(
    () => this.options().find((o) => o.value === this.value())?.label ?? '',
  );

  protected onQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    // Lo tipeado cambia el listado bajo el cursor: dejar el índice donde estaba apuntaba a otra
    // opción, y Enter elegía cualquier cosa.
    this.activeIndex.set(0);
  }

  protected toggle(): void {
    if (this.disabled()) return;
    if (this.open()) {
      this.open.set(false);
    } else {
      this.openPanel();
    }
  }

  protected select(opt: SelectOption): void {
    this.value.set(opt.value);
    this.closeAndRefocus();
  }

  /**
   * Cierra devolviendo el foco al trigger. Necesario con `searchable`: el foco vive en el input
   * del panel, y al desmontarlo se caía al body — el Tab siguiente reempezaba desde el principio
   * del documento en vez de seguir por el formulario.
   */
  private closeAndRefocus(): void {
    const hadFocusInside = this.host.nativeElement.contains(document.activeElement);
    this.open.set(false);
    if (hadFocusInside) {
      this.host.nativeElement.querySelector<HTMLButtonElement>('.trigger')?.focus();
    }
  }

  protected onKeydown(event: KeyboardEvent): void {
    // Con el buscador abierto el espacio es un carácter más de lo que se está tipeando
    // ("Villa Crespo"), no el atajo para elegir la opción activa.
    if (event.key === ' ' && this.open() && this.searchable()) {
      return;
    }
    switch (event.key) {
      case ' ':
      case 'Enter':
        event.preventDefault();
        if (this.open()) {
          const active = this.visibleOptions()[this.activeIndex()];
          if (active) {
            this.select(active);
          }
        } else {
          this.openPanel();
        }
        break;
      case 'ArrowDown':
        event.preventDefault();
        if (this.open()) {
          this.moveActive(1);
        } else {
          this.openPanel();
        }
        break;
      case 'ArrowUp':
        event.preventDefault();
        if (this.open()) {
          this.moveActive(-1);
        }
        break;
      case 'Home':
      case 'End':
        // Con el buscador, Home/End mueven el cursor dentro del texto tipeado.
        if (this.open() && !this.searchable()) {
          event.preventDefault();
          this.activeIndex.set(event.key === 'Home' ? 0 : this.visibleOptions().length - 1);
          this.scrollActiveIntoView();
        }
        break;
      case 'Escape':
        if (this.open()) {
          event.stopPropagation();
          this.closeAndRefocus();
        }
        break;
      case 'Tab':
        // Refocus síncrono: el navegador retoma el Tab desde el trigger y sigue por el
        // formulario, en vez de reempezar desde el body al desmontarse el buscador.
        this.closeAndRefocus();
        break;
    }
  }

  @HostListener('document:click', ['$event'])
  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
    }
  }

  @HostListener('window:resize')
  protected onViewportChange(): void {
    if (this.open()) this.open.set(false);
  }

  private openPanel(): void {
    // Cada apertura arranca con el listado completo: conservar lo tipeado la vez anterior mostraba
    // un panel ya filtrado sin que se viera por qué.
    this.query.set('');
    // Contra lo que se dibuja, no contra el catálogo entero: con `searchable` y muchas opciones la
    // elegida puede caer fuera del tope de resultados, y el índice apuntaba a una fila inexistente.
    const selected = this.visibleOptions().findIndex((o) => o.value === this.value());
    this.activeIndex.set(selected >= 0 ? selected : 0);
    this.positionPanel();
    this.open.set(true);
    this.focusSearch();
    this.scrollActiveIntoView();
  }

  private positionPanel(): void {
    const trigger = this.host.nativeElement.querySelector('.trigger')!.getBoundingClientRect();
    // El panel tiene max-height 40vh, así que nunca crece más que eso por muchas opciones.
    const estHeight = Math.min(
      this.allOptions().length * 40 + 8,
      document.documentElement.clientHeight * 0.4,
    );
    this.panelPos.set(anchorToTrigger(trigger, estHeight, 'stretch'));
  }

  /** El buscador se dibuja recién con el panel abierto → esperar un frame para enfocarlo. */
  private focusSearch(): void {
    if (!this.searchable()) {
      return;
    }
    requestAnimationFrame(() => {
      this.host.nativeElement.querySelector<HTMLInputElement>('.search')?.focus();
    });
  }

  private moveActive(delta: number): void {
    const count = this.visibleOptions().length;
    if (count === 0) {
      return;
    }
    this.activeIndex.update((i) => Math.min(count - 1, Math.max(0, i + delta)));
    this.scrollActiveIntoView();
  }

  /** El DOM se actualiza recién después del CD → esperar un frame antes de scrollear. */
  private scrollActiveIntoView(): void {
    requestAnimationFrame(() => {
      this.host.nativeElement
        .querySelector('.option.active')
        ?.scrollIntoView({ block: 'nearest' });
    });
  }
}
