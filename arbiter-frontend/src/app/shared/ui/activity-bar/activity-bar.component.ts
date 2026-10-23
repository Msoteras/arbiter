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
const TRICKLE_MS = 350;
/** Where the bar slows down to wait: it never reaches the end until the work does. */
const CEILING = 0.92;
/** Time to fill the rest and then to fade out once the last request ends. */
const FILL_MS = 200;
const FADE_MS = 300;

/**
 * Thin progress line pinned to the top of the viewport while the backend is working. The real
 * duration is unknown, so it creeps toward the ceiling and fills up when the last request ends.
 * Driven from code, not a CSS animation: the finish has to start where the bar is, and a removed
 * animation would first snap it back to its base value.
 * `active` overrides the HTTP activity (the styleguide uses it to show the bar on demand).
 */
@Component({
  selector: 'app-activity-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.fixed]': 'active() === null' },
  template: `
    <div
      class="bar"
      [class.idle]="phase() === 'idle'"
      [class.done]="phase() === 'done'"
      [style.transform]="'scaleX(' + progress() + ')'"
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
      /* Above app-loading's veil (200): the home loads behind frosted glass, the bar stays sharp. */
      z-index: 210;
    }
    .bar {
      height: 100%;
      background: var(--accent-fill);
      transform-origin: left;
      transition: transform var(--dur-3) var(--ease-out);
    }
    /* Back to zero without being seen. */
    .bar.idle {
      opacity: 0;
      transition: none;
    }
    .bar.done {
      opacity: 0;
      transition:
        transform var(--dur-2) ease-out,
        opacity var(--dur-3) ease-in var(--dur-2);
    }
    @media (prefers-reduced-motion: reduce) {
      .bar,
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
  protected readonly progress = signal(0);
  private showTimer: ReturnType<typeof setTimeout> | undefined;
  private doneTimer: ReturnType<typeof setTimeout> | undefined;
  private trickle: ReturnType<typeof setInterval> | undefined;

  constructor() {
    effect(() => {
      const active = this.active() ?? this.activity.active();
      untracked(() => (active ? this.begin() : this.finish()));
    });
    inject(DestroyRef).onDestroy(() => this.stopTimers());
  }

  private begin(): void {
    clearTimeout(this.doneTimer);
    if (this.phase() === 'running') return;
    // A new request while the last one fades out picks up from where the bar is.
    if (this.phase() === 'done') {
      this.run(this.progress() >= 1 ? 0.3 : this.progress());
      return;
    }
    clearTimeout(this.showTimer);
    this.showTimer = setTimeout(() => this.run(0.08), SHOW_DELAY_MS);
  }

  private run(from: number): void {
    this.phase.set('running');
    this.progress.set(from);
    clearInterval(this.trickle);
    this.trickle = setInterval(
      () => this.progress.update((p) => p + (CEILING - p) * 0.1),
      TRICKLE_MS,
    );
  }

  private finish(): void {
    clearTimeout(this.showTimer);
    clearInterval(this.trickle);
    if (this.phase() !== 'running') {
      if (this.phase() === 'idle') this.progress.set(0);
      return;
    }
    this.phase.set('done');
    this.progress.set(1);
    this.doneTimer = setTimeout(() => {
      this.phase.set('idle');
      this.progress.set(0);
    }, FILL_MS + FADE_MS);
  }

  private stopTimers(): void {
    clearTimeout(this.showTimer);
    clearTimeout(this.doneTimer);
    clearInterval(this.trickle);
  }
}
