import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { IconComponent, IconName } from '../icon/icon.component';
import { LogoComponent } from '../logo/logo.component';
import { SidebarService } from './sidebar.service';

/** A Lucide icon, or `arbiter`: the brand symbol, for the entry that is Arbiter itself. */
export type SidebarLinkIcon = IconName | 'arbiter';

/**
 * Entry of the sidebar, on the `<a>` itself so `routerLink` and `routerLinkActive` sit on the
 * element they act on: `<a app-sidebar-link icon="inbox" label="Bandeja" routerLink="/inbox"
 * routerLinkActive="active">`. Projected content (a counter, a pending dot) goes after the label.
 * Folded to icons the label stays in the DOM as the accessible name and shows as a tooltip on
 * hover or keyboard focus, as in shadcn/ui.
 */
@Component({
  // An attribute on <a>: the entry has to stay a real anchor for routerLink and the keyboard.
  // eslint-disable-next-line @angular-eslint/component-selector
  selector: 'a[app-sidebar-link]',
  imports: [IconComponent, LogoComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'sidebar-link',
    '[class.is-collapsed]': 'sidebar.collapsed()',
    '(mouseenter)': 'showTip()',
    '(mouseleave)': 'tip.set(null)',
    '(focusin)': 'showTip()',
    '(focusout)': 'tip.set(null)',
    '(click)': 'tip.set(null)',
  },
  template: `
    @if (icon(); as name) {
      @if (name === 'arbiter') {
        <span class="link-icon link-logo"><app-logo variant="symbol" /></span>
      } @else {
        <app-icon [name]="name" class="link-icon" />
      }
    }
    <span class="link-label">{{ label() }}</span>
    <ng-content />
    @if (tipVisible()) {
      <span
        class="tip"
        aria-hidden="true"
        [style.top.px]="tip()!.top"
        [style.left.px]="tip()!.left"
      >
        {{ label() }}
      </span>
    }
  `,
  styles: `
    :host {
      position: relative;
      display: flex;
      align-items: center;
      gap: var(--space-2);
      height: var(--sidebar-item-h);
      padding: 0 var(--space-2);
      border-radius: var(--radius-ctl);
      font-size: var(--font-size-md);
      color: var(--sidebar-fg);
      text-decoration: none;
      white-space: nowrap;
      outline: none;
      transition:
        width var(--dur-2) var(--ease-standard),
        background var(--dur-1) ease;
    }
    :host(:hover) {
      background: var(--sidebar-accent);
      color: var(--sidebar-accent-fg);
    }
    :host(:focus-visible) {
      box-shadow: var(--sidebar-focus-ring);
    }
    :host(.active) {
      background: var(--sidebar-accent);
      color: var(--sidebar-accent-fg);
      font-weight: var(--font-weight-medium);
    }
    :host(.active) .link-icon {
      color: var(--sidebar-active-icon);
    }
    /* The symbol is drawn at 1.15em: this font size makes it as wide as a Lucide icon. */
    .link-logo {
      flex: none;
      display: grid;
      place-items: center;
      width: var(--icon-md);
      height: var(--icon-md);
      --logo-size: var(--font-size-sm);
    }
    :host(.is-collapsed) {
      width: var(--sidebar-item-h);
    }
    .link-label {
      flex: 1 1 auto;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .tip {
      position: fixed;
      z-index: 70;
      transform: translateY(-50%);
      padding: var(--space-1) var(--space-2);
      border-radius: var(--radius-ctl);
      background: var(--surface);
      color: var(--text-primary);
      border: 1px solid var(--border-default);
      box-shadow: var(--shadow-pop);
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-medium);
      pointer-events: none;
      animation: tip-in var(--dur-1) var(--ease-out);
    }
    @keyframes tip-in {
      from {
        opacity: 0;
      }
    }
  `,
})
export class SidebarLinkComponent {
  protected readonly sidebar = inject(SidebarService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly icon = input.required<SidebarLinkIcon>();
  readonly label = input.required<string>();

  protected readonly tip = signal<{ top: number; left: number } | null>(null);
  protected readonly tipVisible = computed(() => this.sidebar.collapsed() && this.tip() !== null);

  protected showTip(): void {
    if (!this.sidebar.collapsed()) return;
    const rect = this.host.nativeElement.getBoundingClientRect();
    // The gap matches --space-3; the tooltip is fixed, so it escapes the panel's overflow.
    this.tip.set({ top: rect.top + rect.height / 2, left: rect.right + 12 });
  }
}
