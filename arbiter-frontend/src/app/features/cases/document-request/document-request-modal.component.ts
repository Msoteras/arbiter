import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { CaseService } from '../case.service';
import { CaseResponse } from '../../../core/models/case';
import {
  DOCUMENT_SUGGESTION_LABELS,
  DocumentRequestItem,
  OTHER_DOCUMENT,
  RequestableDocument,
} from '../../../core/models/case-document';
import { ToastService } from '../../../shared/ui/toast/toast.service';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CheckboxComponent } from '../../../shared/ui/checkbox/checkbox.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';
import { IconButtonComponent } from '../../../shared/ui/icon-button/icon-button.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';

/** A document the catalog doesn't name: what the insured sees, and why, which only the model reads. */
interface OtherDocument {
  id: number;
  description: string;
  reason: string;
}

type OptionsState =
  { status: 'loading' } | { status: 'ok'; options: RequestableDocument[] } | { status: 'error' };

/**
 * The analyst asks the insured for documents. Two steps, so a request isn't sent by mistake: the
 * insured gets an email and the case stops until everything arrives.
 */
@Component({
  selector: 'app-document-request-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ModalComponent,
    ButtonComponent,
    CheckboxComponent,
    InputComponent,
    TextareaComponent,
    IconButtonComponent,
    InlineLoadingComponent,
    InfoTipComponent,
  ],
  template: `
    <app-modal
      [open]="open()"
      size="lg"
      heading="Pedir documentación"
      [dismissable]="false"
      (close)="cancel()"
    >
      @if (step() === 'pick') {
        @switch (optionsState().status) {
          @case ('loading') {
            <app-inline-loading message="Cargando los documentos del ramo…" />
          }
          @case ('error') {
            <p class="t-note error">No se pudieron cargar los documentos del ramo.</p>
          }
        }
        @if (options().length > 0) {
          <fieldset class="group">
            <legend class="t-field-label">Documentos del ramo</legend>
            @for (option of options(); track option.code) {
              <app-checkbox
                [checked]="picked().has(option.code)"
                (checkedChange)="toggle(option.code, $event)"
                >{{ option.name }}
                @if (option.suggestion; as suggestion) {
                  <span class="suggestion">{{ suggestionLabels[suggestion] }}</span>
                }
              </app-checkbox>
            }
          </fieldset>
        }

        <div class="group">
          <span class="t-field-label">Otros documentos</span>
          @for (other of others(); track other.id) {
            <div class="other">
              <div class="other-fields">
                <label class="field">
                  <span class="t-field-label"
                    >Documento <span class="required" aria-hidden="true">*</span></span
                  >
                  <app-input
                    placeholder="Ej.: constancia de la tarjeta de crédito"
                    [value]="other.description"
                    (valueChange)="updateOther(other.id, { description: $event })"
                  />
                </label>
                <label class="field">
                  <span class="t-field-label"
                    >Motivo
                    <span class="required" aria-hidden="true">*</span>
                    <app-info-tip
                      >El asegurado no lo ve. Arbiter lo usa al reclasificar.</app-info-tip
                    >
                  </span>
                  <app-textarea
                    [rows]="2"
                    placeholder="Qué hay que revisar en el documento…"
                    [value]="other.reason"
                    (valueChange)="updateOther(other.id, { reason: $event })"
                  />
                </label>
              </div>
              <app-icon-button
                icon="delete"
                size="sm"
                label="Quitar documento"
                (click)="removeOther(other.id)"
              />
            </div>
          }
          <app-button variant="secondary" size="sm" (click)="addOther()"
            >+ Agregar otro documento</app-button
          >
        </div>
      } @else {
        <p class="t-body">Vas a pedir:</p>
        <ul class="summary">
          @for (line of summary(); track $index) {
            <li class="t-body">{{ line }}</li>
          }
        </ul>
        <p class="t-note">El asegurado recibirá una notificación con los documentos a subir.</p>
      }

      @if (error()) {
        <p class="t-note error">{{ error() }}</p>
      }

      <ng-container modalActions>
        @if (step() === 'pick') {
          <app-button variant="secondary" (click)="cancel()">Cancelar</app-button>
          <app-button [disabled]="!canContinue()" (click)="step.set('confirm')"
            >Continuar</app-button
          >
        } @else {
          <app-button variant="secondary" [disabled]="saving()" (click)="step.set('pick')"
            >Volver</app-button
          >
          <app-button [loading]="saving()" (click)="confirm()">Pedir documentación</app-button>
        }
      </ng-container>
    </app-modal>
  `,
  styles: `
    .group {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      margin: 0 0 var(--space-4);
      padding: 0;
      border: none;
      align-items: flex-start;
    }
    .group legend {
      margin-bottom: var(--space-2);
    }
    .suggestion {
      margin-left: var(--space-2);
      color: var(--text-muted);
      font-size: var(--font-size-sm);
    }
    .other {
      display: flex;
      align-items: flex-start;
      gap: var(--space-2);
      width: 100%;
      padding-bottom: var(--space-3);
      border-bottom: 1px solid var(--border-subtle);
    }
    .other-fields {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      flex: 1;
      min-width: 0;
    }
    .field {
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
    }
    .required {
      color: var(--status-danger);
    }
    .summary {
      margin: 0 0 var(--space-3);
      padding-left: var(--space-4);
    }
    .error {
      color: var(--status-danger);
    }
  `,
})
export class DocumentRequestModalComponent {
  private readonly service = inject(CaseService);
  private readonly toast = inject(ToastService);

  readonly open = input(false);
  readonly caseId = input.required<number>();
  readonly closed = output<void>();
  readonly requested = output<CaseResponse>();

  protected readonly suggestionLabels = DOCUMENT_SUGGESTION_LABELS;
  protected readonly step = signal<'pick' | 'confirm'>('pick');
  protected readonly optionsState = signal<OptionsState>({ status: 'loading' });
  protected readonly picked = signal<ReadonlySet<string>>(new Set());
  protected readonly others = signal<OtherDocument[]>([]);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  private nextOtherId = 0;

  protected readonly options = computed(() => {
    const state = this.optionsState();
    return state.status === 'ok' ? state.options : [];
  });

  /** Every "other" row filled in: a half-written one would reach the insured as a blank slot. */
  protected readonly canContinue = computed(() => {
    const others = this.others();
    const complete = others.every((o) => o.description.trim() && o.reason.trim());
    return complete && this.picked().size + others.length > 0;
  });

  protected readonly summary = computed(() => [
    ...this.options()
      .filter((option) => this.picked().has(option.code))
      .map((option) => option.name),
    ...this.others().map((other) => other.description.trim()),
  ]);

  constructor() {
    // Each opening starts clean and reads the options again: what is requestable changes with
    // every upload and request.
    effect(() => {
      if (!this.open()) return;
      const caseId = this.caseId();
      untracked(() => this.reset(caseId));
    });
  }

  private reset(caseId: number): void {
    this.step.set('pick');
    this.picked.set(new Set());
    this.others.set([]);
    this.error.set(null);
    this.saving.set(false);
    this.optionsState.set({ status: 'loading' });
    this.service.requestableDocuments(caseId).subscribe({
      next: (options) => {
        this.optionsState.set({ status: 'ok', options });
        // What the system suggests comes checked: the analyst takes out what isn't needed.
        this.picked.set(new Set(options.filter((o) => o.suggestion).map((o) => o.code)));
      },
      error: () => this.optionsState.set({ status: 'error' }),
    });
  }

  protected toggle(code: string, checked: boolean): void {
    this.picked.update((current) => {
      const next = new Set(current);
      if (checked) {
        next.add(code);
      } else {
        next.delete(code);
      }
      return next;
    });
  }

  protected addOther(): void {
    this.others.update((list) => [
      ...list,
      { id: this.nextOtherId++, description: '', reason: '' },
    ]);
  }

  protected updateOther(id: number, change: Partial<Omit<OtherDocument, 'id'>>): void {
    this.others.update((list) => list.map((o) => (o.id === id ? { ...o, ...change } : o)));
  }

  protected removeOther(id: number): void {
    this.others.update((list) => list.filter((o) => o.id !== id));
  }

  protected cancel(): void {
    this.closed.emit();
  }

  protected confirm(): void {
    const documents: DocumentRequestItem[] = [
      ...[...this.picked()].map((type) => ({ type })),
      ...this.others().map((other) => ({
        type: OTHER_DOCUMENT,
        description: other.description.trim(),
        reason: other.reason.trim(),
      })),
    ];
    this.saving.set(true);
    this.error.set(null);
    this.service.requestDocuments(this.caseId(), documents).subscribe({
      next: (response) => {
        this.saving.set(false);
        this.toast.show('Se pidió la documentación al asegurado.', 'ok');
        this.requested.emit(response);
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.error.set(err.error?.detail || 'No se pudo pedir la documentación.');
      },
    });
  }
}
