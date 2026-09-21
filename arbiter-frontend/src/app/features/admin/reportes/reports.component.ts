import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import {
  ActivatedRoute,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';

import { ReportFiltersStore } from './report-filters.store';

/**
 * Reports shell: the title, the tab that picks which report to look at, and the period/branch
 * filters both of them share ({@link ReportFiltersStore}).
 *
 * <p>The tabs are routes and not a signal, which is what lets a referent bookmark or paste "the
 * fraud report for August" — and what makes coming back from a case detail land on the tab they
 * left, rather than always on Resolución.
 */
@Component({
  selector: 'app-reports',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  // Here and not on the route: the tabs get it from this component's injector (the router-outlet
  // chains to it), so the store is created and discarded with the reports screen.
  providers: [ReportFiltersStore],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './reports.component.html',
  styleUrl: './reports.component.scss',
})
export class ReportsComponent {
  protected readonly filters = inject(ReportFiltersStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly tabs = [
    { path: 'resolutions', label: 'Resolución de siniestros' },
    { path: 'fraud', label: 'Detección de fraude' },
  ] as const;

  constructor() {
    this.filters.hydrate(this.route.snapshot.queryParamMap);

    // The URL always describes what is on screen, so a link carries the filters and not just the
    // tab. Skipping the first run keeps this from rewriting the URL with what it just read.
    let hydrating = true;
    effect(() => {
      const queryParams = this.filters.asQueryParams();
      if (hydrating) {
        hydrating = false;
        return;
      }
      // navigateByUrl over the current tree, not navigate([], { relativeTo }): relative to the
      // shell route, an empty command list resolves to the parent and the default-child redirect
      // would bounce the fraud tab back to resolutions on every keystroke.
      const tree = this.router.parseUrl(this.router.url);
      tree.queryParams = queryParams;
      void this.router.navigateByUrl(tree, { replaceUrl: true });
    });
  }
}
