import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { SkeletonComponent } from '../../../shared/ui/skeleton/skeleton.component';

/**
 * The home's frame while its data loads: the hero and two columns of cards, in the same layout as
 * the analyst's and the referente's home. On the first load it sits under `app-loading`'s veil, so
 * the screen that's coming is already visible, blurred.
 */
@Component({
  selector: 'app-home-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SkeletonComponent],
  template: `
    <div class="home fit" aria-hidden="true">
      <div class="hero">
        <app-skeleton class="hero-title" [lines]="2" />
        <div class="shortcuts">
          @for (s of [1, 2, 3, 4]; track s) {
            <app-skeleton class="shortcut" [lines]="2" />
          }
        </div>
      </div>
      @if (columns()) {
        <div class="cols">
          <div class="col col-main">
            <app-skeleton class="card tall" shape="block" />
            <app-skeleton class="card grow" shape="block" />
          </div>
          <div class="col col-side">
            <app-skeleton class="card" shape="block" />
            <app-skeleton class="card grow" shape="block" />
          </div>
        </div>
      }
    </div>
  `,
  styleUrl: './home-skeleton.component.scss',
})
export class HomeSkeletonComponent {
  /** The insured's home is a single column of cards under the hero. */
  readonly columns = input(true);
}
