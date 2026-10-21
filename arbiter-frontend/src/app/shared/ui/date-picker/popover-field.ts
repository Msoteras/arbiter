import {
  DestroyRef,
  Directive,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { PanelPosition, anchorPanel } from '../overlay-position';

/**
 * Shared behaviour of the pickers: a trigger button that opens a `fixed` panel next to it.
 *
 * The panel follows the trigger on scroll instead of closing like `app-select` does: on a phone,
 * focusing the time field inside the panel scrolls the page, and closing then would lose the panel
 * the user is typing into.
 */
@Directive()
export abstract class PopoverField {
  private static nextId = 0;

  protected readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly autoId = `app-picker-${PopoverField.nextId++}`;

  readonly id = input<string | null>(null);
  readonly disabled = input(false);
  readonly ariaLabel = input<string | null>(null);

  protected readonly resolvedId = computed(() => this.id() ?? this.autoId);
  protected readonly open = signal(false);
  protected readonly panelPos = signal<PanelPosition>({
    top: null,
    bottom: null,
    left: 0,
    maxHeight: 0,
  });

  /** Estimates, in px: the panel is positioned before it is rendered, so it cannot be measured. */
  protected abstract readonly panelWidth: number;
  protected abstract readonly panelHeight: number;

  constructor() {
    const reposition = () => {
      if (this.open()) this.positionPanel();
    };
    // Outside click in the capture phase: app-modal stops propagation, so a bubbling listener on
    // document would never fire inside a modal.
    const onClick = (event: Event) => {
      if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
        this.closePanel(false);
      }
    };
    document.addEventListener('scroll', reposition, true);
    window.addEventListener('resize', reposition);
    document.addEventListener('click', onClick, true);
    inject(DestroyRef).onDestroy(() => {
      document.removeEventListener('scroll', reposition, true);
      window.removeEventListener('resize', reposition);
      document.removeEventListener('click', onClick, true);
    });
  }

  protected toggle(): void {
    if (this.open()) {
      this.closePanel(false);
    } else {
      this.openPanel();
    }
  }

  protected openPanel(): void {
    if (this.open() || this.disabled()) return;
    this.onOpen();
    this.positionPanel();
    this.open.set(true);
    afterNextRender(
      () => {
        const panel = this.host.nativeElement.querySelector<HTMLElement>('.panel');
        const target =
          panel?.querySelector<HTMLElement>('[data-autofocus]') ??
          panel?.querySelector<HTMLElement>('button:not(:disabled), input');
        target?.focus();
      },
      { injector: this.injector },
    );
  }

  /** @param refocus back to the trigger, so the next Tab continues through the form. */
  protected closePanel(refocus = true): void {
    if (!this.open()) return;
    this.open.set(false);
    if (refocus) {
      this.host.nativeElement.querySelector<HTMLElement>('.trigger')?.focus();
    }
  }

  /** Hook to reset the panel's own state before it opens. */
  protected onOpen(): void {
    return;
  }

  protected onPanelKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      // Stops a dismissable modal around the picker from closing along with it.
      event.stopPropagation();
      this.closePanel();
    }
  }

  /** Tabbing out of the panel closes it. A click on its padding has no target and is ignored. */
  protected onFocusOut(event: FocusEvent): void {
    const next = event.relatedTarget as Node | null;
    if (next && !this.host.nativeElement.contains(next)) {
      this.closePanel(false);
    }
  }

  private positionPanel(): void {
    const trigger = this.host.nativeElement.querySelector('.trigger');
    if (trigger) {
      this.panelPos.set(
        anchorPanel(trigger.getBoundingClientRect(), this.panelHeight, this.panelWidth),
      );
    }
  }
}
