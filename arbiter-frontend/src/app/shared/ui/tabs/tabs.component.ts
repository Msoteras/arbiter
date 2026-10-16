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
      display: flex;
      flex-wrap: inherit;
      gap: var(--space-1);
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
