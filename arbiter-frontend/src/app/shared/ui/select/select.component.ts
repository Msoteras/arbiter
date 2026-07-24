import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  computed,
  inject,
  input,
  model,
  signal,
} from '@angular/core';

export interface SelectOption {
  value: string;
  label: string;
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
 * Teclado: Enter/Espacio/↓ abre · ↑/↓ navega · Enter elige · Esc/Tab/click afuera cierra.
 */
@Component({
  selector: 'app-select',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="select" [class.open]="open()">
      <button
        type="button"
        class="trigger"
        role="combobox"
        aria-haspopup="listbox"
        [attr.aria-expanded]="open()"
        [attr.aria-controls]="open() ? id + '-listbox' : null"
        [attr.aria-activedescendant]="open() ? id + '-opt-' + activeIndex() : null"
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
        <ul class="panel" role="listbox" [id]="id + '-listbox'">
          @for (opt of allOptions(); track opt.value; let i = $index) {
            <li
              class="option"
              role="option"
              [id]="id + '-opt-' + i"
              [class.active]="activeIndex() === i"
              [class.is-placeholder]="opt.value === ''"
              [attr.aria-selected]="value() === opt.value"
              (click)="select(opt)"
              (mousemove)="activeIndex.set(i)"
            >
              {{ opt.label }}
            </li>
          }
        </ul>
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
    .select.open .trigger { outline: none; border-color: var(--action-secondary-border-hover); }
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

    .panel {
      position: absolute;
      top: calc(100% + var(--space-1));
      left: 0;
      right: 0;
      z-index: 50;
      margin: 0;
      padding: var(--space-1);
      list-style: none;
      background: var(--surface);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      box-shadow: var(--shadow-modal);
      max-height: 40vh;
      overflow-y: auto;
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

  protected readonly id = 'app-select-' + SelectComponent.nextId++;

  readonly value = model('');
  readonly options = input<SelectOption[]>([]);
  readonly placeholder = input('');

  protected readonly open = signal(false);
  protected readonly activeIndex = signal(0);

  /** Opciones renderizadas: el placeholder es la opción vacía (permite "limpiar"). */
  protected readonly allOptions = computed<SelectOption[]>(() =>
    this.placeholder()
      ? [{ value: '', label: this.placeholder() }, ...this.options()]
      : this.options(),
  );

  protected readonly selectedLabel = computed(
    () => this.options().find((o) => o.value === this.value())?.label ?? '',
  );

  protected toggle(): void {
    if (this.open()) {
      this.open.set(false);
    } else {
      this.openPanel();
    }
  }

  protected select(opt: SelectOption): void {
    this.value.set(opt.value);
    this.open.set(false);
  }

  protected onKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'Enter':
      case ' ':
        event.preventDefault();
        if (this.open()) {
          const active = this.allOptions()[this.activeIndex()];
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
        if (this.open()) {
          event.preventDefault();
          this.activeIndex.set(event.key === 'Home' ? 0 : this.allOptions().length - 1);
          this.scrollActiveIntoView();
        }
        break;
      case 'Escape':
        if (this.open()) {
          event.stopPropagation();
          this.open.set(false);
        }
        break;
      case 'Tab':
        this.open.set(false);
        break;
    }
  }

  @HostListener('document:click', ['$event'])
  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
    }
  }

  private openPanel(): void {
    const selected = this.allOptions().findIndex((o) => o.value === this.value());
    this.activeIndex.set(selected >= 0 ? selected : 0);
    this.open.set(true);
    this.scrollActiveIntoView();
  }

  private moveActive(delta: number): void {
    const count = this.allOptions().length;
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
