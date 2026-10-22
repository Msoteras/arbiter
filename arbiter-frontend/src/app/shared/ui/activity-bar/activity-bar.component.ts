import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { HttpActivityService } from '../../../core/http/http-activity';

type Phase = 'idle' | 'running' | 'done';

/** A request shorter than this never shows the bar: a flash reads as noise, not as progress. */
const SHOW_DELAY_MS = 180;
/** How long the completed bar stays before it fades out. */
const DONE_MS = 420;

/**
 * Thin progress line pinned to the top of the viewport while the backend is working. The real
 * duration is unknown, so it eases toward 90% and completes when the last request ends.
 * `active` overrides the HTTP activity (the styleguide uses it to show the bar on demand).
 */
@Component({
  selector: 'app-activity-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.fixed]': 'active() === null' },
  template: `
    <div
      class="bar"
      [class.running]="phase() === 'running'"
      [class.done]="phase() === 'done'"
      role="progressbar"
      aria-label="Cargando"
      [attr.aria-hidden]="phase() === 'idle'"
    ></div>
  `,
  styles: `
    :host {
      display: block;
      height: var(--activity-bar-h);
      overflow: hidden;
      pointer-events: none;
    }
    :host(.fixed) {
      position: fixed;
      inset: 0 0 auto;
      z-index: 70;
    }
    .bar {
      height: 100%;
      background: var(--accent-fill);
      transform-origin: left;
      transform: scaleX(0);
      opacity: 0;
    }
    .bar.running {
      opacity: 1;
      animation: activity-run 14s var(--ease-out) forwards;
    }
    .bar.done {
      transform: scaleX(1);
      opacity: 0;
      transition:
        transform var(--dur-2) ease-out,
        opacity var(--dur-3) ease-in var(--dur-2);
    }
    @keyframes activity-run {
      from {
        transform: scaleX(0.08);
      }
      to {
        transform: scaleX(0.9);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .bar.running {
        animation: none;
        transform: scaleX(1);
        opacity: 0.6;
      }
      .bar.done {
        transition: none;
      }
    }
  `,
})
export class ActivityBarComponent {
  private readonly activity = inject(HttpActivityService);
  readonly active = input<boolean | null>(null);

  protected readonly phase = signal<Phase>('idle');
  private showTimer: ReturnType<typeof setTimeout> | undefined;
  private doneTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    effect(() => {
      const active = this.active() ?? this.activity.active();
      untracked(() => (active ? this.begin() : this.finish()));
    });
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.showTimer);
      clearTimeout(this.doneTimer);
    });
  }

  private begin(): void {
    clearTimeout(this.doneTimer);
    if (this.phase() === 'running') return;
    this.showTimer = setTimeout(() => this.phase.set('running'), SHOW_DELAY_MS);
  }

  private finish(): void {
    clearTimeout(this.showTimer);
    if (this.phase() !== 'running') {
      this.phase.set('idle');
      return;
    }
    this.phase.set('done');
    this.doneTimer = setTimeout(() => this.phase.set('idle'), DONE_MS);
  }
}
