import { ChangeDetectionStrategy, Component, ElementRef, inject, signal } from '@angular/core';

import { OverlayPosition, anchorToTrigger } from '../overlay-position';

/**
 * Toggles on click rather than hover so it works on touch and keyboard. The bubble is `fixed` and
 * anchored with {@link anchorToTrigger} so overflow ancestors do not clip it.
 */
@Component({
  selector: 'app-info-tip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:click)': 'onDocumentClick()',
  },
  template: `
    <span class="info-tip">
      <button
        type="button"
        class="trigger"
        [attr.aria-expanded]="open()"
        [attr.aria-label]="open() ? 'Ocultar información' : 'Más información'"
        (click)="toggle($event)"
      >
        <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.3" />
          <path d="M8 7.2v4.1" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
          <circle cx="8" cy="4.9" r="0.9" fill="currentColor" />
        </svg>
      </button>
      @if (open()) {
        <span
          class="bubble"
          role="tooltip"
          [style.top.px]="pos().top"
          [style.bottom.px]="pos().bottom"
          [style.left.px]="pos().left"
          [style.right.px]="pos().right"
          ><ng-content
        /></span>
      }
    </span>
  `,
  styles: `
    .info-tip {
      position: relative;
      display: inline-flex;
      vertical-align: middle;
      margin-left: var(--space-1);
    }
    .trigger {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 18px;
      height: 18px;
      padding: 0;
      border: none;
      background: none;
      border-radius: var(--radius-pill);
      color: var(--text-muted);
      cursor: pointer;
    }
    .trigger:hover,
    .trigger[aria-expanded='true'] {
      color: var(--text-secondary);
    }
    .trigger:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
    }
    .trigger svg {
      width: 15px;
      height: 15px;
    }

    .bubble {
      position: fixed;
      z-index: 50;
      width: max-content;
      /* Do not inherit the host label's text treatment (e.g. uppercase in app-stat-tile). */
      text-transform: none;
      letter-spacing: normal;
      text-align: left;
      /* min() so it stays on screen on mobile. */
      max-width: min(280px, calc(100vw - var(--space-4) * 2));
      padding: var(--space-2) var(--space-3);
      border-radius: var(--radius-ctl);
      border: 1px solid var(--border-default);
      background: var(--surface-head);
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-regular);
      line-height: 1.4;
      box-shadow: var(--shadow-pop);
    }
  `,
})
export class InfoTipComponent {
  /** Used to pick the opening side without measuring the bubble first. */
  private static readonly ESTIMATED_HEIGHT = 140;

  private readonly host = inject(ElementRef<HTMLElement>);

  protected readonly open = signal(false);
  protected readonly pos = signal<OverlayPosition>({
    top: null,
    bottom: null,
    left: null,
    right: null,
    width: null,
  });

  protected toggle(event: MouseEvent): void {
    event.stopPropagation();
    if (!this.open()) {
      const trigger = this.host.nativeElement.getBoundingClientRect();
      // Anchor to the edge away from the nearest screen side, so right-side tips stay on screen.
      const align = trigger.left > document.documentElement.clientWidth / 2 ? 'end' : 'start';
      this.pos.set(anchorToTrigger(trigger, InfoTipComponent.ESTIMATED_HEIGHT, align));
    }
    this.open.update((v) => !v);
  }

  protected onDocumentClick(): void {
    if (this.open()) {
      this.open.set(false);
    }
  }
}
