import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { SpinnerComponent } from '../spinner/spinner.component';

type Variant = 'primary' | 'secondary' | 'accent';
type Size = 'md' | 'sm';
type SavePhase = 'idle' | 'saving' | 'saved';

/** How long "Guardado" stays before the button goes back to its label. */
const SAVED_MS = 1600;

/**
 * `tone` (secondary only) tints border and text with a status color, for actions whose outcome is a
 * status, such as approving or rejecting a case.
 *
 * `saving` turns it into a save button: Guardar → Guardando (the button fills up) → Guardado. When
 * `saving` goes back to false without `saveFailed`, it reads "Guardado" for a moment and then emits
 * `saveSettled`. A form that closes after saving closes on `saveSettled`, so "Guardado" is seen.
 */
@Component({
  selector: 'app-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpinnerComponent],
  host: {
    '[class.block]': 'block()',
    '[class.is-disabled]': "disabled() || loading() || phase() !== 'idle'",
  },
  template: `
    <button
      class="btn"
      [class.primary]="variant() === 'primary'"
      [class.secondary]="variant() === 'secondary'"
      [class.accent]="variant() === 'accent'"
      [attr.data-tone]="variant() === 'secondary' ? tone() : null"
      [class.sm]="size() === 'sm'"
      [class.loading]="loading()"
      [class.saving]="phase() === 'saving'"
      [class.saved]="phase() === 'saved'"
      [type]="type()"
      [disabled]="disabled() || loading() || phase() !== 'idle'"
    >
      @if (loading()) {
        <app-spinner [size]="16" />
      }
      <!-- The save labels share one cell, so the button keeps the width of the longest. -->
      <span class="labels" [attr.aria-live]="saving() === null ? null : 'polite'">
        <span class="label" [class.off]="phase() !== 'idle'"><ng-content /></span>
        @if (saving() !== null) {
          <span class="label" [class.off]="phase() !== 'saving'">Guardando</span>
          <span class="label" [class.off]="phase() !== 'saved'">✓ Guardado</span>
        }
      </span>
    </button>
  `,
  styles: `
    @use 'media';

    :host {
      display: inline-block;
    }
    /* Consumers' (click) lives on the host and fires even if the inner <button> is disabled; a
       HostListener cannot stop it because the consumer's registers first. With no pointer events
       there is no hover either: put a disabled button's title on a wrapper. */
    :host(.is-disabled) {
      pointer-events: none;
    }
    :host(.block) {
      display: block;
    }
    :host(.block) .btn {
      width: 100%;
    }
    .btn {
      font: inherit;
      font-size: var(--font-size-body);
      cursor: pointer;
      padding: var(--space-2) var(--space-4);
      border-radius: var(--radius-ctl);
      border: 1px solid transparent;
      white-space: nowrap;
      transition:
        background-color var(--dur-1) ease,
        border-color var(--dur-1) ease,
        transform var(--dur-1) var(--ease-standard);
    }
    /* Press feedback: the click registers even when the response takes a while. */
    .btn:active:not(:disabled) {
      transform: scale(0.97);
    }
    @media (prefers-reduced-motion: reduce) {
      .btn {
        transition: none;
      }
      .btn:active:not(:disabled) {
        transform: none;
      }
    }
    .btn.loading {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: var(--space-2);
    }
    .btn.sm {
      font-size: var(--font-size-sm);
      padding: var(--space-1) var(--space-3);
    }
    @include media.touch {
      .btn,
      .btn.sm {
        min-height: var(--touch-target);
      }
    }
    .btn.primary {
      background: var(--action-primary-bg);
      border-color: var(--action-primary-bg);
      color: var(--action-primary-fg);
    }
    .btn.primary:hover:not(:disabled) {
      background: var(--action-primary-bg-hover);
      border-color: var(--action-primary-bg-hover);
    }
    .btn.secondary {
      background: var(--action-secondary-bg);
      border-color: var(--action-secondary-border);
      color: var(--action-secondary-fg);
    }
    .btn.secondary:hover:not(:disabled) {
      border-color: var(--action-secondary-border-hover);
    }
    .btn[data-tone='ok'] {
      --btn-tone: var(--status-ok);
    }
    .btn[data-tone='danger'] {
      --btn-tone: var(--status-danger);
    }
    .btn.secondary[data-tone] {
      color: var(--btn-tone);
      border-color: color-mix(in srgb, var(--btn-tone) 45%, var(--action-secondary-border));
    }
    .btn.secondary[data-tone]:hover:not(:disabled) {
      border-color: var(--btn-tone);
    }
    .btn.accent {
      background: var(--action-accent-bg);
      border-color: var(--action-accent-bg);
      color: var(--action-accent-fg);
    }
    .btn.accent:hover:not(:disabled) {
      background: var(--action-accent-bg-hover);
      border-color: var(--action-accent-bg-hover);
    }
    /* [loading] also disables the native <button>; excluding it keeps a loading button in its
       variant colors with the spinner instead of looking disabled. */
    /* Save cycle. The fill eases toward 90% (the real duration is unknown) and completes on success. */
    .btn.primary {
      --btn-fill: var(--action-primary-bg);
    }
    .btn.accent {
      --btn-fill: var(--action-accent-bg);
    }
    .btn.secondary {
      --btn-fill: var(--selected-bg);
    }
    .btn.saving,
    .btn.saved {
      position: relative;
      overflow: hidden;
      isolation: isolate;
      cursor: default;
    }
    .btn.saving {
      background: color-mix(in srgb, var(--btn-fill) 75%, var(--surface));
    }
    .btn.saving::before,
    .btn.saved::before {
      content: '';
      position: absolute;
      inset: 0;
      z-index: -1;
      background: var(--btn-fill);
      transform-origin: left;
    }
    .btn.saving::before {
      animation: save-fill 4s var(--ease-out) forwards;
    }
    .btn.saved::before {
      transform: scaleX(1);
      animation: save-complete var(--dur-2) ease-out;
    }
    @keyframes save-fill {
      from {
        transform: scaleX(0);
      }
      to {
        transform: scaleX(0.9);
      }
    }
    @keyframes save-complete {
      from {
        transform: scaleX(0.9);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .btn.saving::before,
      .btn.saved::before {
        animation: none;
        transform: scaleX(1);
      }
    }
    .labels {
      display: inline-grid;
    }
    .label {
      grid-area: 1 / 1;
    }
    .label.off {
      visibility: hidden;
    }

    .btn:disabled:not(.loading):not(.saving):not(.saved) {
      color: var(--text-muted);
      background: var(--surface-sunken);
      border-color: var(--border-subtle);
      cursor: default;
      pointer-events: none;
    }
  `,
})
export class ButtonComponent {
  readonly variant = input<Variant>('primary');
  readonly size = input<Size>('md');
  readonly type = input<'button' | 'submit'>('button');
  readonly disabled = input(false);
  readonly block = input(false);
  readonly tone = input<'ok' | 'danger' | null>(null);
  /** Shows an inline spinner and disables the button. */
  readonly loading = input(false);
  /** `null`: not a save button. See the class comment. */
  readonly saving = input<boolean | null>(null);
  readonly saveFailed = input(false);
  readonly saveSettled = output<void>();

  protected readonly phase = signal<SavePhase>('idle');
  private settleTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    effect(() => {
      const saving = this.saving();
      untracked(() => {
        if (saving) {
          clearTimeout(this.settleTimer);
          this.phase.set('saving');
        } else if (this.phase() === 'saving') {
          // The consumer sets its error and clears `saving` in the same callback, so both are
          // already up to date here.
          if (this.saveFailed()) {
            this.phase.set('idle');
            return;
          }
          this.phase.set('saved');
          this.settleTimer = setTimeout(() => {
            this.phase.set('idle');
            this.saveSettled.emit();
          }, SAVED_MS);
        }
      });
    });
    inject(DestroyRef).onDestroy(() => clearTimeout(this.settleTimer));
  }
}
