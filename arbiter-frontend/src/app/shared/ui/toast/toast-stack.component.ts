import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChildren,
} from '@angular/core';

import { ButtonComponent } from '../button/button.component';
import { ToastAction, ToastService } from './toast.service';

/** Toasts drawn; older ones wait behind the stack, still counting down. */
const VISIBLE = 3;
/** A downward drag past this many pixels dismisses the toast. */
const SWIPE_DISMISS_PX = 45;

/**
 * Mounted once in `app.html`. Modeled on Sonner (emilkowalski/sonner), rebuilt in Angular with the
 * design system's tokens: the newest toast in front and the next two peeking behind it; hovering or
 * focusing the stack spreads them out and pauses their timers; a toast swipes away downwards.
 * Tone shows only on the icon, never as a fill.
 */
@Component({
  selector: 'app-toast-stack',
  imports: [ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="toaster" aria-label="Avisos">
      <ol
        class="toast-list"
        [class.expanded]="expanded()"
        [style.height.px]="listHeight()"
        (mouseenter)="expand()"
        (mouseleave)="collapse()"
        (focusin)="expand()"
        (focusout)="onFocusOut($event)"
      >
        @for (t of ordered(); track t.id; let i = $index) {
          <li
            #item
            class="toast"
            [attr.data-id]="t.id"
            [attr.data-tone]="t.tone"
            [attr.role]="t.tone === 'danger' ? 'alert' : 'status'"
            [class.mounted]="mounted().has(t.id)"
            [class.leaving]="t.leaving"
            [class.behind]="i > 0"
            [class.hidden]="i >= visible"
            [class.dragging]="dragId() === t.id"
            [style.--index]="i"
            [style.--offset.px]="offset(i)"
            [style.--front-h.px]="frontHeight()"
            [style.--drag.px]="dragId() === t.id ? dragY() : 0"
            [style.z-index]="ordered().length - i"
            (pointerdown)="startDrag($event, t.id)"
            (pointermove)="moveDrag($event, t.id)"
            (pointerup)="endDrag(t.id)"
            (pointercancel)="cancelDrag()"
          >
            <div class="body">
              <span class="icon" aria-hidden="true">
                @switch (t.tone) {
                  @case ('ok') {
                    <svg viewBox="0 0 16 16" fill="none">
                      <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.4" />
                      <path
                        d="m5.2 8.2 1.9 1.9 3.7-3.9"
                        stroke="currentColor"
                        stroke-width="1.4"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                      />
                    </svg>
                  }
                  @case ('danger') {
                    <svg viewBox="0 0 16 16" fill="none">
                      <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.4" />
                      <path
                        d="M8 4.8v3.8"
                        stroke="currentColor"
                        stroke-width="1.4"
                        stroke-linecap="round"
                      />
                      <circle cx="8" cy="11" r="0.85" fill="currentColor" />
                    </svg>
                  }
                  @case ('warning') {
                    <svg viewBox="0 0 16 16" fill="none">
                      <path
                        d="M8 2.2 14.3 13.3H1.7L8 2.2Z"
                        stroke="currentColor"
                        stroke-width="1.4"
                        stroke-linejoin="round"
                      />
                      <path
                        d="M8 6.4v3.4"
                        stroke="currentColor"
                        stroke-width="1.4"
                        stroke-linecap="round"
                      />
                      <circle cx="8" cy="11.4" r="0.8" fill="currentColor" />
                    </svg>
                  }
                  @default {
                    <svg viewBox="0 0 16 16" fill="none">
                      <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.4" />
                      <path
                        d="M8 7.2v4.1"
                        stroke="currentColor"
                        stroke-width="1.4"
                        stroke-linecap="round"
                      />
                      <circle cx="8" cy="4.9" r="0.85" fill="currentColor" />
                    </svg>
                  }
                }
              </span>
              <div class="text">
                <p class="title">{{ t.message }}</p>
                @if (t.description) {
                  <p class="description">{{ t.description }}</p>
                }
              </div>
              @if (t.action; as action) {
                <app-button size="sm" (click)="runAction(t.id, action)">{{
                  action.label
                }}</app-button>
              }
            </div>
            <button
              type="button"
              class="close"
              (click)="service.dismiss(t.id)"
              aria-label="Cerrar aviso"
            >
              <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path
                  d="M4.5 4.5l7 7M11.5 4.5l-7 7"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                />
              </svg>
            </button>
          </li>
        }
      </ol>
    </section>
  `,
  styles: `
    .toast-list {
      position: fixed;
      z-index: 200;
      right: var(--space-5);
      bottom: var(--space-5);
      width: min(var(--toast-w), calc(100vw - 2 * var(--space-5)));
      margin: 0;
      padding: 0;
      list-style: none;
      transition: height 0.25s ease;
    }

    .toast {
      position: absolute;
      right: 0;
      bottom: 0;
      width: 100%;
      box-sizing: border-box;
      padding: var(--space-3) var(--space-4);
      border-radius: var(--radius-card);
      border: 1px solid var(--border-default);
      background: var(--surface);
      box-shadow: var(--shadow-pop);
      touch-action: none;
      /* Entering: from below, transparent. */
      opacity: 0;
      transform: translateY(100%);
      transition:
        transform 0.4s cubic-bezier(0.21, 1.02, 0.73, 1),
        opacity 0.4s ease,
        height 0.4s ease;
    }

    /* Collapsed: the newest in front, the next ones peeking above it, smaller. */
    .toast.mounted {
      opacity: 1;
      transform: translateY(calc(var(--index) * -1 * var(--space-3) + var(--drag)))
        scale(calc(1 - var(--index) * 0.05));
    }
    .toast.behind {
      height: var(--front-h);
      overflow: hidden;
    }
    .toast.behind .body,
    .toast.behind .close {
      opacity: 0;
    }

    /* Expanded on hover or focus: every toast at its own height, one above the other. */
    .expanded .toast.mounted {
      transform: translateY(calc(var(--offset) * -1 + var(--drag)));
    }
    .expanded .toast.behind {
      height: auto;
    }
    .expanded .toast.behind .body,
    .expanded .toast.behind .close {
      opacity: 1;
    }

    .toast.hidden,
    .toast.leaving {
      opacity: 0;
      pointer-events: none;
    }
    .toast.leaving {
      transform: translateY(calc(var(--offset) * -1 + 50%));
    }
    .toast.dragging {
      transition: none;
      cursor: grabbing;
    }

    .body {
      display: flex;
      align-items: flex-start;
      gap: var(--space-3);
      padding-right: var(--space-4);
      transition: opacity 0.4s ease;
    }
    .icon {
      flex-shrink: 0;
      display: inline-flex;
      width: var(--icon-md);
      height: var(--icon-md);
      margin-top: var(--space-1);
      color: var(--status-info);
    }
    .icon svg {
      width: 100%;
      height: 100%;
    }
    .toast[data-tone='ok'] .icon {
      color: var(--status-ok);
    }
    .toast[data-tone='warning'] .icon {
      color: var(--status-warning);
    }
    .toast[data-tone='danger'] .icon {
      color: var(--status-danger);
    }
    .text {
      flex: 1 1 auto;
      min-width: 0;
    }
    .title {
      margin: 0;
      color: var(--text-primary);
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-medium);
      line-height: 1.4;
    }
    .description {
      margin: var(--space-1) 0 0;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      line-height: 1.4;
    }
    .body app-button {
      flex-shrink: 0;
      align-self: center;
    }

    .close {
      position: absolute;
      top: var(--space-2);
      right: var(--space-2);
      display: inline-flex;
      padding: 0;
      border: none;
      background: none;
      color: var(--text-muted);
      cursor: pointer;
      transition: opacity 0.4s ease;
    }
    .close:hover {
      color: var(--text-secondary);
    }
    .close:focus-visible {
      outline: none;
      box-shadow: var(--focus-ring);
      border-radius: var(--radius-ctl);
    }
    .close svg {
      width: var(--icon-sm);
      height: var(--icon-sm);
    }

    @media (prefers-reduced-motion: reduce) {
      .toast,
      .toast-list,
      .body,
      .close {
        transition: none;
      }
    }
  `,
})
export class ToastStackComponent {
  protected readonly service = inject(ToastService);
  protected readonly visible = VISIBLE;

  /** Newest first: it is the one in front. */
  protected readonly ordered = computed(() => [...this.service.toasts()].reverse());

  protected readonly expanded = signal(false);
  /** Ids already on screen for a frame, so their entrance can animate. */
  protected readonly mounted = signal<ReadonlySet<number>>(new Set());
  private readonly heights = signal<ReadonlyMap<number, number>>(new Map());

  protected readonly dragId = signal<number | null>(null);
  protected readonly dragY = signal(0);
  private dragStartY = 0;

  private readonly items = viewChildren<ElementRef<HTMLElement>>('item');
  /** A closed toast's element stays observed, and alive, until it is unobserved. */
  private readonly observed = new Set<HTMLElement>();
  private readonly resize = new ResizeObserver((entries) => {
    this.heights.update((current) => {
      const next = new Map(current);
      for (const entry of entries) {
        const id = Number((entry.target as HTMLElement).dataset['id']);
        next.set(id, (entry.target as HTMLElement).offsetHeight);
      }
      return next;
    });
  });

  /**
   * The gap between expanded toasts, read once from the spacing token. No fallback size: the
   * tokens are global, and a missing one should show as stacked toasts, not as a guessed gap.
   */
  private readonly gap =
    parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--space-3')) || 0;

  protected readonly frontHeight = computed(() => {
    const front = this.ordered()[0];
    return front ? (this.heights().get(front.id) ?? 0) : 0;
  });

  /** What the hover area covers, so the stack doesn't collapse while the pointer crosses a gap. */
  protected readonly listHeight = computed(() => {
    const shown = this.ordered()
      .slice(0, VISIBLE)
      .filter((t) => !t.leaving);
    if (shown.length === 0) {
      return 0;
    }
    if (!this.expanded()) {
      return this.frontHeight() + (shown.length - 1) * this.gap;
    }
    return (
      shown.reduce((sum, t) => sum + (this.heights().get(t.id) ?? 0), 0) +
      (shown.length - 1) * this.gap
    );
  });

  constructor() {
    effect(() => {
      const elements = this.items().map((item) => item.nativeElement);
      this.observed.forEach((element) => {
        if (!elements.includes(element)) {
          this.resize.unobserve(element);
          this.observed.delete(element);
        }
      });
      elements.forEach((element) => {
        this.resize.observe(element);
        this.observed.add(element);
      });
      const live = new Set(this.ordered().map((t) => t.id));
      untracked(() => {
        if ([...this.heights().keys()].some((id) => !live.has(id))) {
          this.heights.update((current) => new Map([...current].filter(([id]) => live.has(id))));
        }
        if ([...this.mounted()].some((id) => !live.has(id))) {
          this.mounted.update((ids) => new Set([...ids].filter((id) => live.has(id))));
        }
      });
      const fresh = this.ordered()
        .map((t) => t.id)
        .filter((id) => !this.mounted().has(id));
      if (fresh.length > 0) {
        // One frame unmounted, so the browser has a starting point for the entrance transition.
        requestAnimationFrame(() => this.mounted.update((ids) => new Set([...ids, ...fresh])));
      }
    });
    // The last toast closing under the pointer fires no mouseleave: without this the timers of
    // the next ones would stay paused.
    effect(() => {
      if (this.ordered().length === 0) {
        untracked(() => this.collapse());
      }
    });
    inject(DestroyRef).onDestroy(() => this.resize.disconnect());
  }

  /** Expanded, a toast sits above the ones in front of it, each at its own height. */
  protected offset(index: number): number {
    const inFront = this.ordered().slice(0, index);
    return inFront.reduce((sum, t) => sum + (this.heights().get(t.id) ?? 0) + this.gap, 0);
  }

  protected expand(): void {
    this.expanded.set(true);
    this.service.pause();
  }

  protected collapse(): void {
    this.expanded.set(false);
    this.service.resume();
  }

  protected onFocusOut(event: FocusEvent): void {
    const list = event.currentTarget as HTMLElement;
    if (!list.contains(event.relatedTarget as Node | null)) {
      this.collapse();
    }
  }

  protected startDrag(event: PointerEvent, id: number): void {
    // Buttons inside keep their own click.
    if ((event.target as HTMLElement).closest('button')) {
      return;
    }
    this.dragId.set(id);
    this.dragStartY = event.clientY;
    this.dragY.set(0);
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }

  protected moveDrag(event: PointerEvent, id: number): void {
    if (this.dragId() !== id) {
      return;
    }
    // Only downwards, towards the edge it came from.
    this.dragY.set(Math.max(0, event.clientY - this.dragStartY));
  }

  protected endDrag(id: number): void {
    if (this.dragId() !== id) {
      return;
    }
    if (this.dragY() > SWIPE_DISMISS_PX) {
      this.service.dismiss(id);
    }
    this.cancelDrag();
  }

  protected cancelDrag(): void {
    this.dragId.set(null);
    this.dragY.set(0);
  }

  protected runAction(id: number, action: ToastAction): void {
    this.service.dismiss(id);
    action.run();
  }
}
