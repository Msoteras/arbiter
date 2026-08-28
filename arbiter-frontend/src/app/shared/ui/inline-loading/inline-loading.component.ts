import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { SpinnerComponent } from '../spinner/spinner.component';

/**
 * Carga "en el lugar": el spinner de marca centrado + un mensaje opcional, SIN tapar la pantalla.
 * Es la contraparte liviana de `app-loading` (que es el overlay a viewport completo, reservado al
 * arranque login → home). Se usa dentro de una pantalla que ya tiene su shell (sidebar/topbar)
 * visible y solo necesita indicar que su contenido se está cargando o refrescando.
 */
@Component({
  selector: 'app-inline-loading',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SpinnerComponent],
  template: `
    <div class="inline-loading" role="status" aria-live="polite">
      <app-spinner [size]="size()" />
      @if (message()) {
        <span class="msg">{{ message() }}</span>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .inline-loading {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: var(--space-3);
      padding: var(--space-6) var(--space-4);
      color: var(--text-secondary);
    }
    .msg {
      font-size: var(--font-size-body);
      color: var(--text-secondary);
    }
  `,
})
export class InlineLoadingComponent {
  /** Diámetro del spinner en px. */
  readonly size = input(24);
  /** Mensaje contextual opcional junto al spinner. */
  readonly message = input('');
}
