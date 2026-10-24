import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterRenderEffect,
  inject,
  input,
  model,
  viewChild,
} from '@angular/core';

export interface TabOption {
  id: string;
  label: string;
  /** A number after the label, e.g. how many items the tab lists. */
  count?: number | null;
  /**
   * A dot after the label: `required` is something missing, `pending` something incomplete,
   * `notice` something new worth a look.
   */
  status?: 'required' | 'pending' | 'notice' | null;
  /** What the dot means, for screen readers; each status has a default. */
  statusLabel?: string;
}

const STATUS_LABELS: Record<NonNullable<TabOption['status']>, string> = {
  required: 'falta configurar',
  pending: 'incompleto',
  notice: 'con novedades',
};

/**
 * Tab strip that switches between sections of one screen. It only owns the strip: the parent
 * renders the panel for `value`. A real `tablist`, so the arrow keys move between tabs and only the
 * selected one sits in the tab order. Content projected into it sits at the end of the strip, on
 * the same line (e.g. filters that are not tabs). For tabs that are routes, see `app-tab-nav`.
 */
@Component({
  selector: 'app-tabs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="strip" [class.is-scrollable]="scrollable()">
      <div class="tablist" role="tablist" [attr.aria-label]="ariaLabel()">
        <span class="ink" #ink aria-hidden="true"></span>
        @for (tab of tabs(); track tab.id) {
          <button
            type="button"
            role="tab"
            class="tab"
            [class.active]="value() === tab.id"
            [attr.aria-selected]="value() === tab.id"
            [attr.tabindex]="value() === tab.id ? 0 : -1"
            (click)="value.set(tab.id)"
            (keydown)="onKeydown($event)"
          >
            {{ tab.label }}
            @if (tab.count != null) {
              <span class="tab-count">{{ tab.count }}</span>
            }
            @if (tab.status) {
              <span
                class="tab-dot"
                [class.is-required]="tab.status === 'required'"
                [class.is-notice]="tab.status === 'notice'"
                aria-hidden="true"
              ></span>
              <span class="sr-only">({{ tab.statusLabel ?? statusLabels[tab.status] }})</span>
            }
          </button>
        }
      </div>
      <ng-content />
    </div>
  `,
  styles: `
    @use 'tab-strip';

    :host {
      display: block;
    }
    @include tab-strip.styles;
    .tablist {
      position: relative;
      display: flex;
      flex-wrap: inherit;
      gap: var(--space-1);
    }
    /* One underline that slides to the selected tab instead of jumping between tabs. Placed from
       code; until then (and if the tab wraps to another row) each tab keeps its own border. */
    .ink {
      position: absolute;
      left: 0;
      bottom: -1px;
      height: 2px;
      width: 0;
      border-radius: var(--radius-pill);
      background: var(--accent-fill);
      opacity: 0;
      pointer-events: none;
    }
    .ink.placed {
      opacity: 1;
    }
    .ink.animated {
      transition:
        transform var(--dur-3) var(--ease-out),
        width var(--dur-3) var(--ease-out);
    }
    .tablist.has-ink .tab.active {
      border-bottom-color: transparent;
    }
    @media (prefers-reduced-motion: reduce) {
      .ink.animated {
        transition: none;
      }
    }
  `,
})
export class TabsComponent {
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);

  readonly tabs = input<readonly TabOption[]>([]);
  readonly value = model('');
  readonly ariaLabel = input<string | null>(null);
  /** On touch screens, one row that scrolls sideways instead of wrapping: for many tabs. */
  readonly scrollable = input(false);

  protected readonly statusLabels = STATUS_LABELS;

  private readonly ink = viewChild.required<ElementRef<HTMLElement>>('ink');

  constructor() {
    // Re-placed on every selection and whenever the strip changes size (fonts, wrapping, counts).
    const observer = new ResizeObserver(() => this.placeInk());
    inject(DestroyRef).onDestroy(() => observer.disconnect());
    let observing = false;
    afterRenderEffect(() => {
      this.value();
      this.tabs();
      if (!observing) {
        observer.observe(this.host.nativeElement);
        observing = true;
      }
      this.placeInk();
    });
  }

  private placeInk(): void {
    const ink = this.ink().nativeElement;
    const list = ink.parentElement as HTMLElement;
    const active = list.querySelector<HTMLElement>('.tab.active');
    // A tab on a second row would put the line under the first: let its own border show instead.
    if (!active || active.offsetTop !== (list.querySelector<HTMLElement>('.tab')?.offsetTop ?? 0)) {
      ink.classList.remove('placed');
      list.classList.remove('has-ink');
      return;
    }
    ink.style.width = `${active.offsetWidth}px`;
    ink.style.transform = `translateX(${active.offsetLeft}px)`;
    if (!ink.classList.contains('placed')) {
      ink.classList.add('placed');
      list.classList.add('has-ink');
      // The first placement lands in place; only later moves slide.
      requestAnimationFrame(() => ink.classList.add('animated'));
    }
  }

  protected onKeydown(event: KeyboardEvent): void {
    const tabs = this.tabs();
    const current = tabs.findIndex((tab) => tab.id === this.value());
    const next = nextIndex(event.key, current, tabs.length);
    if (next === null) {
      return;
    }
    event.preventDefault();
    this.value.set(tabs[next].id);
    const buttons =
      this.host.nativeElement.querySelectorAll<HTMLButtonElement>('.tablist > [role="tab"]');
    buttons[next]?.focus();
  }
}

/** Where an arrow, Home or End lands, wrapping at both ends; null for any other key. */
function nextIndex(key: string, current: number, count: number): number | null {
  if (count === 0) {
    return null;
  }
  switch (key) {
    case 'ArrowRight':
      return (current + 1) % count;
    case 'ArrowLeft':
      return (current - 1 + count) % count;
    case 'Home':
      return 0;
    case 'End':
      return count - 1;
    default:
      return null;
  }
}
