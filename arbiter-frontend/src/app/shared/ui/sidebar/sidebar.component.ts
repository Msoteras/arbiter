import { ChangeDetectionStrategy, Component, HostListener, inject, input } from '@angular/core';

import { SidebarService } from './sidebar.service';

/**
 * App sidebar in the style of shadcn/ui's `collapsible="icon"` block: header, scrollable groups
 * and footer, folding down to the icons. The host is the gap the sidebar takes in the layout and
 * animates its width; the panel itself is fixed to the viewport, so it stays put while the page
 * scrolls. Below the sheet cut the gap is gone and the panel slides in over a scrim.
 *
 * Slots: `[sidebarHeader]`, the default one for `app-sidebar-group`s, `[sidebarFooter]`.
 * Ctrl/⌘+B toggles it, like in shadcn/ui; the rail on its edge does the same with the mouse.
 */
@Component({
  selector: 'app-sidebar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class.is-collapsed]': 'sidebar.collapsed()',
    '[class.is-mobile]': 'sidebar.isMobile()',
    '[class.is-open]': 'sidebar.mobileOpen()',
  },
  template: `
    @if (sidebar.isMobile() && sidebar.mobileOpen()) {
      <div class="scrim" aria-hidden="true" (click)="sidebar.closeMobile()"></div>
    }
    <div
      class="panel"
      [attr.id]="panelId()"
      [attr.inert]="sidebar.isMobile() && !sidebar.mobileOpen() ? '' : null"
    >
      <div class="header"><ng-content select="[sidebarHeader]" /></div>
      <nav class="content" [attr.aria-label]="label()"><ng-content /></nav>
      <div class="footer"><ng-content select="[sidebarFooter]" /></div>
      @if (!sidebar.isMobile()) {
        <button
          type="button"
          class="rail"
          tabindex="-1"
          [attr.aria-label]="
            sidebar.expanded() ? 'Contraer barra lateral' : 'Expandir barra lateral'
          "
          [attr.title]="sidebar.expanded() ? 'Contraer barra lateral' : 'Expandir barra lateral'"
          (click)="sidebar.toggle()"
        ></button>
      }
    </div>
  `,
  styles: `
    @use 'media';

    :host {
      display: block;
      flex: none;
      width: var(--sidebar-w);
      transition: width var(--dur-2) var(--ease-standard);
    }
    :host(.is-collapsed) {
      width: var(--sidebar-w-icon);
    }
    :host(.is-mobile) {
      width: 0;
    }

    .panel {
      position: fixed;
      top: 0;
      bottom: 0;
      left: 0;
      z-index: 45;
      display: flex;
      flex-direction: column;
      width: var(--sidebar-w);
      background: var(--sidebar-bg);
      color: var(--sidebar-fg);
      border-right: 1px solid var(--sidebar-border);
      transition: width var(--dur-2) var(--ease-standard);
    }
    :host(.is-collapsed) .panel {
      width: var(--sidebar-w-icon);
    }

    .header,
    .footer {
      flex: none;
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      padding: var(--space-2);
    }
    /* A shell without a header slot leaves the box empty: it must not take room. */
    .header:empty {
      display: none;
    }
    .content {
      flex: 1 1 auto;
      min-height: 0;
      display: flex;
      flex-direction: column;
      overflow-x: hidden;
      overflow-y: auto;
    }
    :host(.is-collapsed) .content {
      overflow-y: hidden;
    }

    /* A strip on the outer edge: hovering it draws the edge line, clicking folds the panel. */
    .rail {
      position: absolute;
      top: 0;
      bottom: 0;
      right: calc(-1 * var(--sidebar-rail-w) / 2);
      width: var(--sidebar-rail-w);
      padding: 0;
      border: none;
      background: none;
      cursor: w-resize;
    }
    :host(.is-collapsed) .rail {
      cursor: e-resize;
    }
    .rail::after {
      content: '';
      position: absolute;
      top: 0;
      bottom: 0;
      left: 50%;
      width: 2px;
      transform: translateX(-50%);
      transition: background var(--dur-1) ease;
    }
    .rail:hover::after {
      background: var(--sidebar-border);
    }

    /* Phone: a sheet over the content, with the labels. */
    :host(.is-mobile) .panel {
      width: var(--sidebar-w-mobile);
      max-width: 85vw;
      box-shadow: var(--shadow-modal);
      transform: translateX(-100%);
      visibility: hidden;
      transition:
        transform var(--dur-2) var(--ease-standard),
        visibility 0s linear var(--dur-2);
    }
    :host(.is-mobile.is-open) .panel {
      transform: none;
      visibility: visible;
      transition: transform var(--dur-2) var(--ease-standard);
    }
    .scrim {
      position: fixed;
      inset: 0;
      z-index: 44;
      background: var(--overlay-backdrop);
    }

    @media (prefers-reduced-motion: reduce) {
      :host,
      .panel,
      :host(.is-mobile) .panel,
      :host(.is-mobile.is-open) .panel {
        transition: none;
      }
    }
  `,
})
export class SidebarComponent {
  protected readonly sidebar = inject(SidebarService);

  /** Accessible name of the navigation landmark. */
  readonly label = input('Navegación principal');
  /** For the trigger's aria-controls. */
  readonly panelId = input('app-sidebar');

  @HostListener('document:keydown', ['$event'])
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key.toLowerCase() === 'b' && (event.metaKey || event.ctrlKey) && !event.altKey) {
      event.preventDefault();
      this.sidebar.toggle();
    }
    if (event.key === 'Escape' && this.sidebar.isMobile() && this.sidebar.mobileOpen()) {
      this.sidebar.closeMobile();
    }
  }
}
