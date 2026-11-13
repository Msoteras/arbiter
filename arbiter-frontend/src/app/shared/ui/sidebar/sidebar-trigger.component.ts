import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';

import { IconComponent } from '../icon/icon.component';
import { SidebarService } from './sidebar.service';

/** Square ghost button that folds or opens the sidebar. Goes at the start of the top bar. */
@Component({
  selector: 'app-sidebar-trigger',
  imports: [IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      class="trigger"
      [attr.aria-controls]="controls()"
      [attr.aria-expanded]="sidebar.isMobile() ? sidebar.mobileOpen() : sidebar.expanded()"
      [attr.aria-label]="label()"
      [attr.title]="label() + ' (Ctrl+B)'"
      (click)="sidebar.toggle()"
    >
      <app-icon name="panel-left" />
    </button>
  `,
  styles: `
    @use 'media';

    :host {
      display: inline-flex;
    }
    .trigger {
      display: grid;
      place-items: center;
      width: var(--icon-btn);
      height: var(--icon-btn);
      padding: 0;
      border: none;
      border-radius: var(--radius-ctl);
      background: none;
      color: var(--chrome-fg);
      cursor: pointer;
      transition: background var(--dur-1) ease;
    }
    .trigger:hover {
      background: var(--chrome-item-active);
    }
    .trigger:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
    }
    @include media.touch {
      .trigger {
        width: var(--touch-target);
        height: var(--touch-target);
      }
    }
  `,
})
export class SidebarTriggerComponent {
  protected readonly sidebar = inject(SidebarService);
  readonly controls = input('app-sidebar');

  protected label(): string {
    if (this.sidebar.isMobile()) {
      return this.sidebar.mobileOpen() ? 'Cerrar menú' : 'Abrir menú';
    }
    return this.sidebar.expanded() ? 'Contraer barra lateral' : 'Expandir barra lateral';
  }
}
