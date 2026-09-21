import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { ReportFormat } from './resolution-report';

/**
 * «Ver vista previa» plus the two export buttons, shared by both report tabs.
 *
 * <p>One component and not the same markup twice: the rule that a format in flight disables the
 * other one, and that a broken period disables all three, is the kind of thing that gets fixed in
 * one copy and not the other.
 */
@Component({
  selector: 'app-report-actions',
  imports: [ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="actions">
      <app-button [disabled]="disabled()" [loading]="loading()" (click)="preview.emit()">
        Ver vista previa
      </app-button>

      <div class="export" role="group" aria-label="Exportar reporte">
        <span class="t-field-label">Exportar</span>
        @for (format of formats; track format) {
          <app-button
            variant="secondary"
            [disabled]="disabled() || (exporting() !== null && exporting() !== format)"
            [loading]="exporting() === format"
            (click)="exportAs.emit(format)"
          >
            {{ format }}
          </app-button>
        }
      </div>
    </div>
  `,
  styles: `
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
      margin-top: var(--space-4);
    }
    .export {
      display: flex;
      align-items: center;
      gap: var(--space-2);
    }

    @media (max-width: 480px) {
      .actions {
        flex-direction: column;
        align-items: stretch;
      }
      .export {
        justify-content: space-between;
      }
    }
  `,
})
export class ReportActionsComponent {
  /** A period the backend would refuse: nothing can be asked for, preview or file. */
  readonly disabled = input(false);
  readonly loading = input(false);
  /** The format being generated, if any: the other button waits its turn. */
  readonly exporting = input<ReportFormat | null>(null);

  readonly preview = output<void>();
  readonly exportAs = output<ReportFormat>();

  protected readonly formats: ReportFormat[] = ['CSV', 'PDF'];
}
