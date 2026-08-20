import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { ButtonComponent } from '../button/button.component';

/**
 * Pie de una sección editable: a la izquierda el estado, a la derecha descartar y guardar.
 *
 * Existe para que las pantallas de configuración digan siempre lo mismo sobre lo mismo. Antes cada
 * sección tenía su propio botón "Guardar X" siempre habilitado, y un "✓ Guardado" que aparecía
 * después de guardar y no se iba nunca — así que el referente no tenía forma de saber si lo que
 * estaba viendo ya estaba persistido o eran cambios suyos sin guardar.
 *
 * `dirty` es lo que gobierna todo: sin cambios no hay nada que guardar ni que descartar, y los dos
 * botones se apagan. `canSave` es la validación aparte (ej. un número fuera de rango): se separa de
 * `dirty` para que el botón deshabilitado signifique una sola cosa por vez.
 */
@Component({
  selector: 'app-save-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonComponent],
  template: `
    <div class="save-bar">
      <span class="state" [class.dirty]="dirty()">
        @if (error()) {
          <span class="error">{{ error() }}</span>
        } @else if (dirty()) {
          Cambios sin guardar
        } @else {
          <span aria-hidden="true">✓</span> Todo guardado
        }
      </span>
      <div class="actions">
        <app-button
          variant="secondary"
          [disabled]="!dirty() || saving()"
          (click)="discard.emit()"
        >
          Descartar
        </app-button>
        <app-button [disabled]="!dirty() || saving() || !canSave()" (click)="save.emit()">
          {{ saving() ? 'Guardando…' : saveLabel() }}
        </app-button>
      </div>
    </div>
  `,
  styles: `
    /* width:100% y no solo display:block: metida en un contenedor flex (pasó en dos solapas), el
       host se encogía al ancho del contenido y los botones terminaban pegados a la izquierda. */
    :host { display: block; width: 100%; }
    .save-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-4);
      flex-wrap: wrap;
      margin-top: var(--space-5);
      padding-top: var(--space-4);
      border-top: 1px solid var(--border-default);
    }
    .actions { display: flex; align-items: center; gap: var(--space-2); }
    /* Guardado en verde de estado; pendiente en gris y no en rojo: tener cambios sin guardar no es
       un error, y pintarlo de alarma le pone urgencia a algo que el referente hace a propósito. */
    .state { font-size: var(--font-size-sm); color: var(--status-ok); }
    .state.dirty { color: var(--text-secondary); }
    .error { color: var(--status-danger); }
  `,
})
export class SaveBarComponent {
  /** Hay algo distinto de lo último que confirmó el backend. */
  readonly dirty = input(false);
  readonly saving = input(false);
  /** Mensaje del backend cuando el guardado falló; reemplaza al estado mientras esté. */
  readonly error = input<string | null>(null);
  /** Validación propia de la sección. `false` bloquea guardar aunque haya cambios. */
  readonly canSave = input(true);
  readonly saveLabel = input('Guardar cambios');

  readonly save = output<void>();
  readonly discard = output<void>();
}
