import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Params, RouterLink, RouterLinkActive } from '@angular/router';

export interface TabLink {
  /** Router path, relative to the component that renders the strip. */
  path: string;
  label: string;
}

/**
 * Tab strip where each tab is a route, so a tab can be bookmarked or shared. Links and not an
 * ARIA tablist: `aria-current` is what they really are. Same look as `app-tabs`.
 */
@Component({
  selector: 'app-tab-nav',
  imports: [RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="strip" [attr.aria-label]="ariaLabel()">
      @for (tab of tabs(); track tab.path) {
        <a
          class="tab"
          [routerLink]="tab.path"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
          [queryParams]="queryParams()"
        >
          {{ tab.label }}
        </a>
      }
    </nav>
  `,
  styles: `
    @use 'tab-strip';

    :host {
      display: block;
    }
    @include tab-strip.styles;
  `,
})
export class TabNavComponent {
  readonly tabs = input<readonly TabLink[]>([]);
  /** Carried to every tab, e.g. filters the tabs share. */
  readonly queryParams = input<Params | null>(null);
  readonly ariaLabel = input<string | null>(null);
}
