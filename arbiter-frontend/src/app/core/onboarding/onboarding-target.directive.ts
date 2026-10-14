import { DestroyRef, Directive, ElementRef, effect, inject, input } from '@angular/core';

import { OnboardingStepId } from './onboarding';
import { OnboardingService } from './onboarding.service';

const HIGHLIGHT_MS = 2400;

/**
 * Marks the control a setup checklist step points at. Arriving from the checklist or the toast,
 * the screen scrolls to it and highlights it for a moment, also when it renders after a load.
 */
@Directive({ selector: '[appOnboardingTarget]' })
export class OnboardingTargetDirective {
  readonly appOnboardingTarget = input.required<OnboardingStepId>();

  private readonly el: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly onboarding = inject(OnboardingService);
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      if (this.onboarding.claimFocus(this.appOnboardingTarget())) {
        this.highlight();
      }
    });
    inject(DestroyRef).onDestroy(() => this.clearTimer());
  }

  private highlight(): void {
    const host = this.el.nativeElement;
    this.clearTimer();
    host.classList.remove('onboarding-target');
    // The next frame: the host was just created and may not have its final position yet.
    requestAnimationFrame(() => {
      const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      // Centered, a section taller than the screen would show its middle and not its title.
      const tall = host.getBoundingClientRect().height > window.innerHeight * 0.7;
      // Before scrolling: the class carries the scroll margin that keeps it below the topbar.
      host.classList.add('onboarding-target');
      host.scrollIntoView({
        behavior: reduceMotion ? 'auto' : 'smooth',
        block: tall ? 'start' : 'center',
      });
      this.timer = setTimeout(() => host.classList.remove('onboarding-target'), HIGHLIGHT_MS);
    });
  }

  private clearTimer(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
