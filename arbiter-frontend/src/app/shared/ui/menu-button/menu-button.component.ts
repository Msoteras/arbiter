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
  /** Destructive action: rendered separated from the rest, in the danger color. */
  danger?: boolean;
}

interface ClosableMenu {
  closeFromRegistry(): void;
}

/**
 * Keeps a single menu open. Relying on each menu's `document:click` is not enough: callers may
 * `stopPropagation` (e.g. a table cell avoiding row navigation), so a menu never hears about another.
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

/** The trigger label is projected; options are passed as data so the panel always looks the same. */
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
    :host {
      display: inline-block;
    }
    :host(.block) {
      display: block;
    }
    :host(.block) .menu {
      width: 100%;
    }
    .menu {
      position: relative;
    }

    /* fixed, not absolute, so overflow ancestors do not clip it (see anchorToTrigger). */
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
    .option:hover {
      background: var(--surface-sunken);
      color: var(--text-primary);
    }

    .heading {
      padding: var(--space-1) var(--space-3) var(--space-2);
      font-size: var(--font-size-2xs);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--text-muted);
    }
    .danger-sep {
      border-top: 1px solid var(--border-subtle);
      margin-top: var(--space-1);
      padding-top: var(--space-1);
    }
    .option.danger {
      color: var(--status-danger);
    }
    .option.danger:hover {
      background: var(--surface-sunken);
      color: var(--status-danger);
    }
  `,
})
export class MenuButtonComponent implements ClosableMenu {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly registry = inject(MenuButtonRegistry);

  constructor() {
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(() => this.registry.close(this));

    // The `fixed` panel does not follow scrolling, so close it instead. Capture phase, because
    // scroll events from inner containers do not bubble to window.
    const onScroll = () => {
      if (this.open()) this.close();
    };
    document.addEventListener('scroll', onScroll, true);
    destroyRef.onDestroy(() => document.removeEventListener('scroll', onScroll, true));
  }

  readonly items = input.required<MenuItem[]>();
  /** Non-clickable title above the options. */
  readonly heading = input<string | null>(null);
  readonly disabled = input(false);
  readonly block = input(false);
  readonly size = input<'md' | 'sm'>('md');
  /** Trigger edge the panel is anchored to; `end` for triggers near the right edge. */
  readonly align = input<'start' | 'end'>('start');

  readonly itemSelected = output<string>();

  protected readonly open = signal(false);
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
      // Position before rendering so the panel does not flicker into place.
      this.positionPanel();
      this.open.set(true);
      this.registry.open(this);
    }
  }

  private close(): void {
    this.open.set(false);
    this.registry.close(this);
  }

  /** Closes without notifying the registry back, which would recurse. */
  closeFromRegistry(): void {
    this.open.set(false);
  }

  private positionPanel(): void {
    const estPanelHeight = (this.heading() ? 28 : 0) + this.items().length * 40 + 12;
    this.panelPos.set(
      anchorToTrigger(
        this.host.nativeElement.getBoundingClientRect(),
        estPanelHeight,
        this.align(),
      ),
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
