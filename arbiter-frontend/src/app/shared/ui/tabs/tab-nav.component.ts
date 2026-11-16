import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  input,
} from '@angular/core';
import { Params, RouterLink, RouterLinkActive } from '@angular/router';

import { watchFolderFit } from './folder-fit';

export interface TabLink {
  /** Router path, relative to the component that renders the strip. */
  path: string;
  label: string;
}

/**
 * Tab strip where each tab is a route, so a tab can be bookmarked or shared. Links and not an
 * ARIA tablist: `aria-current` is what they really are. Same look as `app-tabs`, `folder` variant
 * included (`[tabsLead]` for the title, `align`).
 */
@Component({
  selector: 'app-tab-nav',
  imports: [NgTemplateOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (variant() === 'folder') {
      <div class="folder" [class.align-start]="align() === 'start'">
        <div class="folder-lead">
          <div class="folder-lead-content"><ng-content select="[tabsLead]" /></div>
        </div>
        <span class="folder-fill folder-pre" aria-hidden="true"></span>
        <nav class="tablist" [attr.aria-label]="ariaLabel()">
          <ng-container *ngTemplateOutlet="links" />
        </nav>
        <div class="folder-fill folder-trail"></div>
      </div>
    } @else {
      <nav class="strip" [attr.aria-label]="ariaLabel()">
        <ng-container *ngTemplateOutlet="links" />
      </nav>
    }

    <ng-template #links>
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
    </ng-template>
  `,
  styles: `
    @use 'tab-strip';

    :host {
      display: block;
    }
    @include tab-strip.styles;
    @include tab-strip.folder-styles;
  `,
})
export class TabNavComponent {
  readonly tabs = input<readonly TabLink[]>([]);
  /** Carried to every tab, e.g. filters the tabs share. */
  readonly queryParams = input<Params | null>(null);
  readonly ariaLabel = input<string | null>(null);
  readonly variant = input<'underline' | 'folder'>('underline');
  /** Folder only: tabs on the right of the lead (`end`) or right after it (`start`). */
  readonly align = input<'start' | 'end'>('end');

  constructor() {
    watchFolderFit(inject<ElementRef<HTMLElement>>(ElementRef).nativeElement, inject(DestroyRef));
  }
}
