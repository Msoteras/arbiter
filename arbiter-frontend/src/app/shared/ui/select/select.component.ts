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

const SEARCH_RESULT_LIMIT = 100;

/** Excludes control keys, browser shortcuts and space, which still opens the trigger. */
function isPrintable(event: KeyboardEvent): boolean {
  return (
    event.key.length === 1 && event.key !== ' ' && !event.ctrlKey && !event.metaKey && !event.altKey
  );
}

/** Accent- and case-insensitive, so "cordoba" matches "Córdoba". */
function normalize(text: string): string {
  return text
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase();
}

/**
 * Custom listbox rather than a native `<select>`, whose panel is drawn by the OS and ignores the
 * tokens. `placeholder` is the empty option; omit it for required fields.
 *
 * `searchable` turns the trigger into a filter input. Typed text only filters: closing without
 * picking restores the selected label, so free text never becomes the value.
 */
@Component({
  selector: 'app-select',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="select" [class.open]="open()">
      @if (searchable()) {
        <input
          type="text"
          class="trigger is-input"
          [id]="resolvedId()"
          role="combobox"
          aria-autocomplete="list"
          autocomplete="off"
          [attr.aria-expanded]="open()"
          [attr.aria-controls]="open() ? resolvedId() + '-listbox' : null"
          [attr.aria-activedescendant]="open() ? resolvedId() + '-opt-' + activeIndex() : null"
          [disabled]="disabled()"
          [placeholder]="placeholder()"
          [value]="displayValue()"
          [class.is-invalid]="invalid()"
          [attr.aria-required]="required() ? 'true' : null"
          [attr.aria-invalid]="invalid() ? 'true' : null"
          [attr.aria-describedby]="invalid() ? resolvedId() + '-error' : null"
          (click)="openPanel()"
          (input)="onQuery($event)"
          (keydown)="onKeydown($event)"
        />
      } @else {
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
          [class.is-invalid]="invalid()"
          [attr.aria-required]="required() ? 'true' : null"
          [attr.aria-invalid]="invalid() ? 'true' : null"
          [attr.aria-describedby]="invalid() ? resolvedId() + '-error' : null"
          (click)="toggle()"
          (keydown)="onKeydown($event)"
        >
          <span class="trigger-label" [class.is-placeholder]="!selectedLabel()">
            {{ selectedLabel() || placeholder() }}
          </span>
        </button>
      }

      <svg
        class="chevron"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.8"
        aria-hidden="true"
      >
        <polyline points="6 9 12 15 18 9" stroke-linecap="round" stroke-linejoin="round" />
      </svg>

      @if (open()) {
        <ul
          class="panel"
          role="listbox"
          [id]="resolvedId() + '-listbox'"
          [style.top.px]="panelPos().top"
          [style.bottom.px]="panelPos().bottom"
          [style.left.px]="panelPos().left"
          [style.width.px]="panelPos().width"
        >
          @for (opt of visibleOptions(); track opt.value; let i = $index) {
            <li
              class="option"
              role="option"
              [id]="resolvedId() + '-opt-' + i"
              [class.active]="activeIndex() === i"
              [class.is-placeholder]="opt.value === ''"
              [attr.aria-selected]="value() === opt.value"
              (mousedown)="$event.preventDefault()"
              (click)="$event.preventDefault(); select(opt)"
              (mousemove)="activeIndex.set(i)"
            >
              {{ opt.label }}
            </li>
          } @empty {
            <li class="hint" role="presentation">Sin resultados</li>
          }
          <!-- Rendering is capped for long catalogs; hundreds of <li> per keystroke is noticeable. -->
          @if (hiddenCount() > 0) {
            <li class="hint" role="presentation">
              +{{ hiddenCount() }} más — seguí escribiendo para achicar la lista
            </li>
          }
        </ul>
      }
    </div>

    <!-- Outside .select: the chevron is centered against it, so a taller child would shift it. -->
    @if (invalid()) {
      <p class="error-msg" [id]="resolvedId() + '-error'" role="alert">{{ requiredMessage() }}</p>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .select {
      position: relative;
    }

    .trigger {
      width: 100%;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
      font: inherit;
      font-size: var(--font-size-lg);
      text-align: left;
      /* Room on the right for the overlaid chevron. */
      padding: var(--space-2) calc(var(--space-3) + 1.5em) var(--space-2) var(--space-3);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: var(--surface);
      color: var(--text-primary);
      cursor: pointer;
    }
    .trigger:focus-visible,
    .select.open .trigger {
      outline: none;
      border-color: var(--border-focus);
      box-shadow: var(--focus-ring);
    }
    .trigger:disabled {
      cursor: default;
      opacity: 0.55;
    }
    .trigger.is-invalid {
      border-color: var(--status-danger);
    }
    .error-msg {
      margin: var(--space-1) 0 0;
      color: var(--status-danger);
      font-size: var(--font-size-sm);
    }
    .trigger-label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .trigger-label.is-placeholder {
      color: var(--text-muted);
    }

    .trigger.is-input {
      display: block;
      cursor: text;
      padding-right: calc(var(--space-3) + 1.5em);
      text-overflow: ellipsis;
    }
    .trigger.is-input::placeholder {
      color: var(--text-muted);
    }

    .chevron {
      width: 1em;
      height: 1em;
      flex-shrink: 0;
      color: var(--text-tertiary);
      transition: transform 0.12s;
      /* Overlaid and click-through, so clicks reach the input underneath and place the caret. */
      position: absolute;
      right: var(--space-3);
      top: 50%;
      transform: translateY(-50%);
      pointer-events: none;
    }
    .select.open .chevron {
      transform: translateY(-50%) rotate(180deg);
    }

    /* fixed, not absolute, so overflow ancestors do not clip it (see anchorToTrigger). */
    .panel {
      position: fixed;
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
    .option.active {
      background: var(--surface-sunken);
      color: var(--text-primary);
    }
    .option[aria-selected='true'] {
      background: var(--surface-head);
      color: var(--text-primary);
      font-weight: var(--font-weight-medium);
    }
    .option.is-placeholder {
      color: var(--text-muted);
    }

    /* 16px on mobile prevents iOS zoom on focus. */
    @media (min-width: 640px) {
      .trigger,
      .option {
        font-size: var(--font-size-body);
      }
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
  readonly disabled = input(false);
  readonly id = input<string | null>(null);
  readonly searchable = input(false);
  /**
   * Flags the field once the panel is closed without a choice (outside click, Esc, Tab). Closing
   * still works, so the user is never trapped in the field.
   */
  readonly required = input(false);
  readonly requiredMessage = input('Elegí una opción para continuar.');

  protected readonly resolvedId = computed(() => this.id() ?? this.autoId);

  protected readonly open = signal(false);
  protected readonly touched = signal(false);
  protected readonly activeIndex = signal(0);
  protected readonly query = signal('');
  protected readonly panelPos = signal<OverlayPosition>({
    top: null,
    bottom: null,
    left: null,
    right: null,
    width: null,
  });

  constructor() {
    // The `fixed` panel does not follow outer scrolling, so close it (capture phase: inner scroll
    // events do not bubble). Scrolling inside the panel's own list must not close it.
    const onScroll = (event: Event) => {
      if (!this.open()) return;
      const panel = this.host.nativeElement.querySelector('.panel');
      const target = event.target as Node | null;
      if (panel && target && panel.contains(target)) return;
      this.closePanel();
    };
    document.addEventListener('scroll', onScroll, true);

    // Outside click in the capture phase rather than @HostListener: app-modal stops propagation,
    // so inside a modal a bubbling listener on document never fires.
    const onClick = (event: Event) => {
      if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
        this.closePanel();
      }
    };
    document.addEventListener('click', onClick, true);

    inject(DestroyRef).onDestroy(() => {
      document.removeEventListener('scroll', onScroll, true);
      document.removeEventListener('click', onClick, true);
    });
  }

  protected readonly allOptions = computed<SelectOption[]>(() =>
    this.placeholder()
      ? [{ value: '', label: this.placeholder() }, ...this.options()]
      : this.options(),
  );

  protected readonly matchingOptions = computed<SelectOption[]>(() => {
    const q = normalize(this.query().trim());
    if (!this.searchable() || !q) {
      return this.allOptions();
    }
    // The placeholder is the empty option, not a search result.
    return this.options().filter((o) => normalize(o.label).includes(q));
  });

  protected readonly visibleOptions = computed(() =>
    this.searchable() ? this.matchingOptions().slice(0, SEARCH_RESULT_LIMIT) : this.allOptions(),
  );

  protected readonly hiddenCount = computed(
    () => this.matchingOptions().length - this.visibleOptions().length,
  );

  protected readonly invalid = computed(
    () => this.required() && this.touched() && this.value() === '',
  );

  protected readonly selectedLabel = computed(
    () => this.options().find((o) => o.value === this.value())?.label ?? '',
  );

  /** Reverting to the selected label on close is what keeps free text out of the value. */
  protected readonly displayValue = computed(() =>
    this.open() ? this.query() : this.selectedLabel(),
  );

  protected onQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    // The list changed under the cursor; a stale index would make Enter pick the wrong option.
    this.activeIndex.set(0);
  }

  protected toggle(): void {
    if (this.disabled()) return;
    if (this.open()) {
      this.closePanel();
    } else {
      this.openPanel();
    }
  }

  protected select(opt: SelectOption): void {
    this.value.set(opt.value);
    this.closeAndRefocus();
  }

  /** Single close path: also marks the field as touched, which enables the required warning. */
  private closePanel(): void {
    this.open.set(false);
    this.touched.set(true);
  }

  /** Keeps focus on the field so the next Tab continues through the form. */
  private closeAndRefocus(): void {
    const hadFocusInside = this.host.nativeElement.contains(document.activeElement);
    this.closePanel();
    if (hadFocusInside) {
      this.host.nativeElement.querySelector<HTMLElement>('.trigger')?.focus();
    }
  }

  protected onKeydown(event: KeyboardEvent): void {
    // Typing on the closed field opens the panel already filtered by that key.
    if (!this.open() && this.searchable() && isPrintable(event)) {
      event.preventDefault();
      this.openPanel(event.key);
      return;
    }
    // While searching, space is part of the query ("Villa Crespo"), not a selection shortcut.
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
        // While searching, Home/End move the caret within the query.
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
        // Synchronous refocus so the browser continues Tab from the trigger, not from the body.
        this.closeAndRefocus();
        break;
    }
  }

  @HostListener('window:resize')
  protected onViewportChange(): void {
    if (this.open()) this.closePanel();
  }

  /** @param initialQuery the key that opened the panel; otherwise empty, so no stale filter shows. */
  protected openPanel(initialQuery = ''): void {
    // Reopening would clear the query; a click on the open field just moves the caret.
    if (this.open() || this.disabled()) {
      return;
    }
    this.query.set(initialQuery);
    // With a query, the first match is the candidate. Otherwise look up the selection among the
    // rendered options: with a capped list it may not be rendered at all.
    const selected = initialQuery
      ? -1
      : this.visibleOptions().findIndex((o) => o.value === this.value());
    this.activeIndex.set(selected >= 0 ? selected : 0);
    this.positionPanel();
    this.open.set(true);
    this.focusTrigger();
    this.scrollActiveIntoView();
  }

  private positionPanel(): void {
    const trigger = this.host.nativeElement.querySelector('.trigger')!.getBoundingClientRect();
    // Matches the panel's max-height of 40vh.
    const estHeight = Math.min(
      this.allOptions().length * 40 + 8,
      document.documentElement.clientHeight * 0.4,
    );
    this.panelPos.set(anchorToTrigger(trigger, estHeight, 'stretch'));
  }

  /** Opening from elsewhere than the input does not focus it, and typed keys would go nowhere. */
  private focusTrigger(): void {
    const trigger = this.host.nativeElement.querySelector<HTMLElement>('.trigger');
    if (!trigger || trigger === document.activeElement) {
      return;
    }
    trigger.focus();
    if (trigger instanceof HTMLInputElement) {
      // `focus()` puts the caret at 0; move it to the end so the next key appends.
      const end = trigger.value.length;
      trigger.setSelectionRange(end, end);
    }
  }

  private moveActive(delta: number): void {
    const count = this.visibleOptions().length;
    if (count === 0) {
      return;
    }
    this.activeIndex.update((i) => Math.min(count - 1, Math.max(0, i + delta)));
    this.scrollActiveIntoView();
  }

  /** Waits a frame: the DOM only updates after change detection. */
  private scrollActiveIntoView(): void {
    requestAnimationFrame(() => {
      this.host.nativeElement.querySelector('.option.active')?.scrollIntoView({ block: 'nearest' });
    });
  }
}
