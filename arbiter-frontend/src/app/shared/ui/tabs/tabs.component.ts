import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  input,
  model,
} from '@angular/core';

export interface TabOption {
  id: string;
  label: string;
}

/**
 * Tab strip that switches between sections of one screen. It only owns the strip: the parent
 * renders the panel for `value`. A real `tablist`, so the arrow keys move between tabs and only the
 * selected one sits in the tab order.
 */
@Component({
  selector: 'app-tabs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tabs" role="tablist" [attr.aria-label]="ariaLabel()">
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
        </button>
      }
    </div>
  `,
  styles: `
    @use 'media';

    :host {
      display: block;
    }
    .tabs {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-1);
      border-bottom: 1px solid var(--border-default);
    }
    .tab {
      font: inherit;
      font-size: var(--font-size-sm);
      padding: var(--space-2) var(--space-3);
      border: none;
      background: none;
      color: var(--text-secondary);
      cursor: pointer;
      border-bottom: 2px solid transparent;
      margin-bottom: -1px;
    }
    @include media.touch {
      .tab {
        min-height: var(--touch-target);
      }
    }
    .tab:hover {
      color: var(--text-primary);
    }
    .tab:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
    }
    .tab.active {
      color: var(--accent-fg);
      border-bottom-color: var(--accent-fill);
      font-weight: var(--font-weight-medium);
    }
  `,
})
export class TabsComponent {
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);

  readonly tabs = input<readonly TabOption[]>([]);
  readonly value = model('');
  readonly ariaLabel = input<string | null>(null);

  protected onKeydown(event: KeyboardEvent): void {
    const tabs = this.tabs();
    const current = tabs.findIndex((tab) => tab.id === this.value());
    const next = nextIndex(event.key, current, tabs.length);
    if (next === null) {
      return;
    }
    event.preventDefault();
    this.value.set(tabs[next].id);
    this.host.nativeElement.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
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
