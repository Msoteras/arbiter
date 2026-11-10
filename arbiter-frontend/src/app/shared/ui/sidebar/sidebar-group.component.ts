import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';

import { SidebarService } from './sidebar.service';

/** A labelled run of `a[app-sidebar-link]`s. Folded to icons, the label fades and its room closes. */
@Component({
  selector: 'app-sidebar-group',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.is-collapsed]': 'sidebar.collapsed()' },
  template: `
    @if (label()) {
      <p class="label" [attr.aria-hidden]="sidebar.collapsed() ? 'true' : null">{{ label() }}</p>
    }
    <div class="menu"><ng-content /></div>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      padding: var(--space-2);
    }
    .label {
      display: flex;
      align-items: center;
      height: var(--sidebar-item-h);
      margin: 0;
      padding: 0 var(--space-2);
      overflow: hidden;
      white-space: nowrap;
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-medium);
      color: var(--sidebar-muted);
      transition:
        margin var(--dur-2) var(--ease-standard),
        opacity var(--dur-2) var(--ease-standard);
    }
    :host(.is-collapsed) .label {
      margin-top: calc(-1 * var(--sidebar-item-h));
      opacity: 0;
    }
    .menu {
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
    }
    @media (prefers-reduced-motion: reduce) {
      .label {
        transition: none;
      }
    }
  `,
})
export class SidebarGroupComponent {
  protected readonly sidebar = inject(SidebarService);
  readonly label = input<string>();
}
