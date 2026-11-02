import {
  ChangeDetectionStrategy,
  Component,
  InjectionToken,
  Signal,
  computed,
  input,
} from '@angular/core';

/** What a control inside an `app-field` reads to point at the field's error. */
export interface FieldContext {
  readonly errorId: Signal<string | null>;
}

export const FIELD_CONTEXT = new InjectionToken<FieldContext>('FIELD_CONTEXT');

/**
 * A form field: label, control and error, with the same spacing in every form. `for` is the id of
 * the control (`app-input`, `app-textarea`, `app-select`…); without it the field is a group
 * named by the label (a set of checkboxes, a date and a time), like a fieldset with its legend.
 * While `error` is set, an `app-input` or `app-textarea` inside is marked invalid and announces
 * the message.
 *
 * `layout="inline"` puts a short control (an amount, a number of days) right after its label, on
 * the same line, so a settings panel stays compact. An `app-info-tip` marked `fieldTip` goes
 * right after the label; an element marked `fieldAside` (e.g. "Olvidé mi contraseña") goes at the
 * right end of the label row. `required` adds the asterisk after the label.
 */
@Component({
  selector: 'app-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [{ provide: FIELD_CONTEXT, useExisting: FieldComponent }],
  template: `
    <div
      class="field"
      [class.inline]="layout() === 'inline'"
      [attr.role]="for() ? null : 'group'"
      [attr.aria-labelledby]="for() ? null : labelId"
      [attr.aria-describedby]="for() ? null : errorId()"
    >
      <div class="label-row">
        @if (for()) {
          <label class="label" [for]="for()"
            >{{ label() }}
            @if (required()) {
              <span class="required" aria-hidden="true">*</span>
            }
          </label>
        } @else {
          <span class="label" [id]="labelId"
            >{{ label() }}
            @if (required()) {
              <span class="required" aria-hidden="true">*</span>
            }
          </span>
        }
        <ng-content select="[fieldTip]" />
        <span class="aside"><ng-content select="[fieldAside]" /></span>
      </div>
      <div class="control"><ng-content /></div>
      @if (error()) {
        <p class="error" [id]="errorId()" role="alert">{{ error() }}</p>
      }
    </div>
  `,
  styles: `
    @use 'media';
    :host {
      display: block;
      min-width: 0;
    }
    .field {
      display: grid;
      gap: var(--space-2);
    }
    .label-row {
      display: flex;
      align-items: center;
      gap: var(--space-1);
      min-width: 0;
    }
    .label {
      font-size: var(--font-size-body);
      font-weight: var(--font-weight-medium);
      color: var(--text-tertiary);
    }
    .required {
      color: var(--status-danger);
    }
    .aside {
      margin-left: auto;
      font-size: var(--font-size-sm);
    }
    .aside:empty {
      display: none;
    }
    .error {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--status-danger);
    }
    .field.inline {
      grid-template-columns: auto auto;
      justify-content: start;
      align-items: center;
      column-gap: var(--space-3);
    }
    .field.inline .error {
      grid-column: 1 / -1;
    }
    @include media.phone {
      .field.inline {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class FieldComponent implements FieldContext {
  readonly label = input.required<string>();
  readonly for = input<string | null>(null);
  readonly error = input<string | null>(null);
  readonly layout = input<'stacked' | 'inline'>('stacked');
  /** Only the asterisk: the control itself says whether it may be left empty. */
  readonly required = input(false);

  readonly errorId = computed(() => (this.error() ? (this.for() ?? this.autoId) + '-error' : null));

  private static nextId = 0;
  private readonly autoId = 'app-field-' + FieldComponent.nextId++;
  protected readonly labelId = this.autoId + '-label';
}
