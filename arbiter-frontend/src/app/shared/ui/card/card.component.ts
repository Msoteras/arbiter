import { ChangeDetectionStrategy, Component, computed, input, linkedSignal } from '@angular/core';

type Variant = 'default' | 'soft' | 'ai';

/**
 * `ai` is the teal wash for model output cards. With `collapsible` the body is hidden, not
 * destroyed, so whatever the user opened inside stays open when expanded again.
 */
@Component({
  selector: 'app-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="card"
      [class.soft]="variant() === 'soft'"
      [class.ai]="variant() === 'ai'"
      [class.flush]="flush()"
      [class.bare]="bare()"
    >
      @if (heading()) {
        @if (isCollapsible()) {
          <h2 class="card-head as-toggle" [class.is-open]="expanded()">
            <button
              type="button"
              class="card-toggle"
              [attr.aria-expanded]="expanded()"
              [attr.aria-controls]="bodyId()"
              (click)="toggle()"
            >
              <span class="chev" [class.is-open]="expanded()" aria-hidden="true">›</span>
              @if (icon()) {
                <span class="icon" aria-hidden="true">{{ icon() }}</span>
              }
              <span class="card-title">{{ heading() }}</span>
            </button>
          </h2>
        } @else {
          <div class="card-head">
            @if (icon()) {
              <span class="icon" aria-hidden="true">{{ icon() }}</span>
            }
            <h2 class="card-title">{{ heading() }}</h2>
          </div>
        }
      }
      <div class="card-body" [id]="bodyId()" [hidden]="isCollapsible() && !expanded()">
        <ng-content />
      </div>
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    .card {
      border: 1px solid var(--border-default);
      border-radius: var(--radius-card);
      background: var(--surface);
      padding: var(--space-4);
      box-shadow: var(--shadow-card);
      /* min-height, not height: fills a stretched grid/flex cell but never shrinks below its
         content (height:100% does inside an auto-height flex column). */
      min-height: 100%;
    }
    .card.soft {
      background: var(--surface-soft);
    }
    .card.ai {
      background: var(--surface-ai);
      border-color: var(--border-ai);
    }
    .card.bare {
      border: none;
      box-shadow: none;
      background: transparent;
      padding: 0;
      min-height: 0;
    }
    .card.flush {
      padding: 0;
      overflow-x: auto;
    }
    .card.flush .card-head {
      margin: var(--space-4) var(--space-4) var(--space-3);
    }
    .card-head {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      margin-bottom: var(--space-3);
    }
    .card-head.as-toggle {
      margin: 0;
      font-size: inherit;
      font-weight: inherit;
    }
    .card-head.as-toggle.is-open {
      margin-bottom: var(--space-3);
    }
    .card-toggle {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      width: 100%;
      padding: 0;
      border: none;
      background: none;
      cursor: pointer;
      text-align: left;
      color: inherit;
      font: inherit;
    }
    .card-toggle:hover .card-title {
      color: var(--text-secondary);
    }
    .card-toggle:focus-visible {
      outline: 2px solid var(--border-focus);
      outline-offset: 2px;
      border-radius: var(--radius-ctl);
    }
    .chev {
      display: inline-block;
      color: var(--text-tertiary);
      transition: transform var(--dur-1) ease;
    }
    .chev.is-open {
      transform: rotate(90deg);
    }
    @media (prefers-reduced-motion: reduce) {
      .chev {
        transition: none;
      }
    }
    .icon {
      color: var(--text-primary);
    }
    .card.ai .card-head .icon {
      color: var(--accent-fg);
    }
    .card.ai .card-head .card-title {
      color: var(--accent-fg);
    }
    .card-title {
      margin: 0;
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-medium);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-tertiary);
    }
  `,
})
export class CardComponent {
  private static instances = 0;

  readonly variant = input<Variant>('default');
  readonly heading = input('');
  readonly icon = input('');
  readonly flush = input(false);
  /** No border/background/padding, for when the outer container already is the box. */
  readonly bare = input(false);
  /** Requires `heading`. */
  readonly collapsible = input(false);
  /** Initial state; only applies with `collapsible`. */
  readonly collapsed = input(false);

  protected readonly isCollapsible = computed(() => this.collapsible() && !!this.heading());

  /** Resets to `collapsed` when the binding changes; the user controls it in between. */
  protected readonly expanded = linkedSignal(() => !this.collapsed());

  private readonly instanceId = `card-${++CardComponent.instances}`;

  protected readonly bodyId = computed(() => `${this.instanceId}-body`);

  protected toggle(): void {
    this.expanded.update((v) => !v);
  }
}
