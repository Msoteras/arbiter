import { ChangeDetectionStrategy, Component, computed, input, model, signal } from '@angular/core';

/** Deliberately not a ControlValueAccessor: the app does not use Angular Forms. */
@Component({
  selector: 'app-input',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="input-wrap">
      @if (prefix()) {
        <span class="prefix" aria-hidden="true">{{ prefix() }}</span>
      }
      <input
        class="field"
        [class.has-reveal]="showReveal()"
        [class.has-prefix]="!!prefix()"
        [class.align-end]="align() === 'end'"
        [attr.inputmode]="inputmode()"
        [id]="resolvedId()"
        [type]="effectiveType()"
        [placeholder]="placeholder()"
        [value]="value()"
        [attr.min]="min()"
        [attr.max]="max()"
        [attr.autocomplete]="autocomplete()"
        [readOnly]="readonly()"
        (keydown)="onKeydown($event)"
        (input)="onInput($event)"
      />
      @if (showReveal()) {
        <button
          type="button"
          class="reveal"
          tabindex="-1"
          [attr.aria-label]="revealed() ? 'Ocultar contraseña' : 'Mostrar contraseña'"
          [attr.aria-pressed]="revealed()"
          (click)="toggleReveal()"
        >
          @if (revealed()) {
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.6"
              aria-hidden="true"
            >
              <path
                d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
              <line x1="1" y1="1" x2="23" y2="23" stroke-linecap="round" />
            </svg>
          } @else {
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.6"
              aria-hidden="true"
            >
              <path
                d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
              <circle cx="12" cy="12" r="3" />
            </svg>
          }
        </button>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .input-wrap {
      position: relative;
    }
    .field[readonly] {
      background: var(--surface-soft);
      color: var(--text-secondary);
      cursor: default;
    }
    .field {
      width: 100%;
      font: inherit;
      /* 16px on mobile prevents iOS Safari from zooming on focus. */
      font-size: var(--font-size-lg);
      padding: var(--space-2) var(--space-3);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: var(--surface);
      color: var(--text-primary);
    }
    .field.has-reveal {
      padding-right: calc(var(--space-2) + 30px);
    }
    /* Hide Edge's native password reveal so it does not duplicate ours. */
    .field.has-reveal::-ms-reveal,
    .field.has-reveal::-ms-clear {
      display: none;
    }
    @media (min-width: 640px) {
      .field {
        font-size: var(--font-size-body);
      }
    }
    .field:focus {
      outline: none;
      border-color: var(--border-focus);
      box-shadow: var(--focus-ring);
    }
    .field::placeholder {
      color: var(--text-muted);
    }
    .prefix {
      position: absolute;
      top: 50%;
      left: var(--space-3);
      transform: translateY(-50%);
      color: var(--text-muted);
      pointer-events: none;
    }
    .field.has-prefix {
      padding-left: calc(var(--space-3) + var(--space-4));
    }
    .field.align-end {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }

    .reveal {
      position: absolute;
      top: 50%;
      right: var(--space-1);
      transform: translateY(-50%);
      display: flex;
      align-items: center;
      justify-content: center;
      width: 30px;
      height: 30px;
      padding: 0;
      border: none;
      background: none;
      border-radius: var(--radius-ctl);
      color: var(--text-muted);
      cursor: pointer;
    }
    .reveal:hover {
      color: var(--text-secondary);
    }
    .reveal svg {
      width: 18px;
      height: 18px;
    }
  `,
})
export class InputComponent {
  private static autoIdCounter = 0;
  private readonly autoId = `app-input-${InputComponent.autoIdCounter++}`;

  readonly value = model('');
  readonly type = input<'text' | 'number' | 'email' | 'date' | 'password' | 'time' | 'tel'>('text');
  readonly id = input<string | null>(null);
  readonly placeholder = input('');
  /** Number for a numeric field, `yyyy-MM-dd` for a date one — it goes straight to `attr.min`. */
  readonly min = input<string | number | null>(null);
  readonly max = input<string | null>(null);
  readonly autocomplete = input<string | null>(null);
  readonly readonly = input(false);
  readonly revealable = input(false);
  readonly prefix = input<string | null>(null);
  readonly align = input<'start' | 'end'>('start');
  /** Mobile keyboard hint, e.g. `numeric` for an amount typed as text so it can show separators. */
  readonly inputmode = input<string | null>(null);

  /** The native `min` is enforced by the arrows and submit, but not by typing or pasting "-500". */
  private readonly rejectsNegative = computed(
    () => this.type() === 'number' && this.min() !== null && Number(this.min()) >= 0,
  );

  protected readonly resolvedId = computed(() => this.id() ?? this.autoId);
  protected readonly revealed = signal(false);
  protected readonly showReveal = computed(() => this.revealable() && this.type() === 'password');
  protected readonly effectiveType = computed(() =>
    this.showReveal() && this.revealed() ? 'text' : this.type(),
  );

  protected onKeydown(event: KeyboardEvent): void {
    if (this.rejectsNegative() && (event.key === '-' || event.key === 'Subtract')) {
      event.preventDefault();
    }
  }

  protected onInput(event: Event): void {
    const field = event.target as HTMLInputElement;
    // Pasting does not go through keydown; drop the sign but keep the number.
    if (this.rejectsNegative() && field.value.startsWith('-')) {
      field.value = field.value.slice(1);
    }
    this.value.set(field.value);
  }

  protected toggleReveal(): void {
    this.revealed.update((v) => !v);
  }
}
