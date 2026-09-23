import { ChangeDetectionStrategy, Component, effect, input, signal } from '@angular/core';

import { StatusTone } from '../../../core/models/status-tone';

type Tone = 'default' | 'accent' | 'danger';

/**
 * `loading` shows a dash while the count is in flight, rather than a 0 that reads as "nothing".
 */
@Component({
  selector: 'app-stat-tile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stat" [class.accent]="tone() === 'accent'" [class.danger]="tone() === 'danger'">
      <span class="stat-label">
        {{ label() }}
        <!-- A projected app-info-tip rather than a text input, so the kit's bubble behavior is kept. -->
        <ng-content select="app-info-tip" />
      </span>
      <span class="stat-value tabular">{{ display() }}</span>
      @if (sub()) {
        <span class="stat-sub">{{ sub() }}</span>
      }
      @if (progress() !== null) {
        <span class="stat-bar tone-{{ progressTone() }}" aria-hidden="true">
          <span class="stat-fill" [style.width.%]="(progress() ?? 0) * 100"></span>
        </span>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .stat {
      display: flex;
      flex-direction: column;
      height: 100%;
      padding: var(--space-4);
      border: 1px solid var(--border-default);
      border-radius: var(--radius-card);
      background: var(--surface);
      box-shadow: var(--shadow-card);
    }
    .stat-label {
      display: flex;
      align-items: center;
      gap: var(--space-1);
      font-size: var(--font-size-2xs);
      font-weight: var(--font-weight-medium);
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--text-muted);
    }
    .stat-value {
      margin-top: var(--space-2);
      font-size: var(--font-size-2xl);
      font-weight: var(--font-weight-bold);
      line-height: 1;
      letter-spacing: -0.02em;
      color: var(--text-primary);
    }
    .stat-sub {
      margin-top: var(--space-1);
      font-size: var(--font-size-xs);
      color: var(--text-muted);
    }
    .stat.accent {
      background: var(--selected-bg);
      border-color: var(--selected-border);
    }
    .stat.accent .stat-value {
      color: var(--accent-fg);
    }
    .stat.danger {
      background: var(--surface-danger-soft);
      border-color: var(--border-danger-soft);
    }
    .stat.danger .stat-value {
      color: var(--status-danger);
    }
    /* Pushed to the bottom so bars in a row line up regardless of subtitle length. */
    .stat-bar {
      display: block;
      margin-top: auto;
      padding-top: var(--space-3);
      position: relative;
    }
    .stat-fill,
    .stat-bar::before {
      display: block;
      height: 3px;
      border-radius: var(--radius-pill);
    }
    .stat-bar::before {
      content: '';
      background: var(--surface-sunken);
    }
    .stat-fill {
      position: absolute;
      left: 0;
      bottom: 0;
    }
    .stat-bar.tone-ok .stat-fill {
      background: var(--status-ok);
    }
    .stat-bar.tone-info .stat-fill {
      background: var(--status-info);
    }
    .stat-bar.tone-warning .stat-fill {
      background: var(--status-warning);
    }
    .stat-bar.tone-risk .stat-fill {
      background: var(--status-risk);
    }
    .stat-bar.tone-danger .stat-fill {
      background: var(--status-danger);
    }
    .stat-bar.tone-neutral .stat-fill {
      background: var(--text-primary);
    }
  `,
})
export class StatTileComponent {
  readonly label = input('');
  readonly value = input<string | number>('');
  readonly sub = input('');
  readonly tone = input<Tone>('default');
  readonly loading = input(false);
  /** 0–1 ratio for the bar under the value; null hides it. Only meaningful when the value is a rate. */
  readonly progress = input<number | null>(null);
  readonly progressTone = input<StatusTone>('neutral');

  /** Numeric values count up from 0 once, unless prefers-reduced-motion is set. */
  protected readonly display = signal<string | number>('—');

  constructor() {
    effect((onCleanup) => {
      const v = this.value();
      if (this.loading()) {
        this.display.set('—');
        return;
      }
      if (typeof v !== 'number' || !Number.isFinite(v)) {
        this.display.set(v);
        return;
      }
      const reduce =
        typeof window !== 'undefined' &&
        window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
      if (reduce || v <= 0) {
        this.display.set(v);
        return;
      }
      const target = v;
      const duration = 650;
      let startTs: number | null = null;
      let raf = 0;
      const step = (ts: number) => {
        if (startTs === null) startTs = ts;
        const p = Math.min(1, (ts - startTs) / duration);
        const eased = 1 - Math.pow(1 - p, 3);
        this.display.set(Math.round(target * eased));
        if (p < 1) raf = requestAnimationFrame(step);
      };
      raf = requestAnimationFrame(step);
      onCleanup(() => cancelAnimationFrame(raf));
    });
  }
}
