import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CardComponent } from '../../../shared/ui/card/card.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { SaveBarComponent } from '../../../shared/ui/save-bar/save-bar.component';
import { SwitchComponent } from '../../../shared/ui/switch/switch.component';
import {
  ResolutionTarget,
  ResolutionTargetService,
  TARGET_DAYS_MAX,
  TARGET_DAYS_MIN,
} from '../resolution-target.service';

/**
 * El objetivo de resolución de la aseguradora: en cuántos días se propone cerrar un siniestro.
 *
 * Es configuración de toda la compañía y no de un ramo, por eso vive en "Reglas generales". Y es
 * una **meta de gestión**, no el plazo legal: ese corre por expediente, lo fija la ley y pasarse es
 * un problema regulatorio. Este lo fija el referente, puede ser más exigente, y lo único que hace
 * es dar una vara contra la cual leer el tiempo promedio del tablero.
 *
 * No lo evalúa el motor de reglas ni bloquea nada. Un expediente que se pasa del objetivo no cambia
 * de estado ni pierde el Fast Track: sigue su curso y aparece contado en el tablero.
 */
@Component({
  selector: 'app-objetivo-config',
  imports: [
    CardComponent,
    InfoTipComponent,
    InlineLoadingComponent,
    InputComponent,
    SaveBarComponent,
    SwitchComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (loading()) {
      <app-inline-loading [size]="26" message="Cargando el objetivo…" />
    } @else {
      <app-card>
        <div class="head">
          <h2 class="card-title">Objetivo de resolución</h2>
          <app-info-tip
            text="Cuántos días se propone la compañía para resolver un siniestro, de la denuncia a
                  la decisión del analista. Es una meta propia, no el plazo legal: el tablero la usa
                  para mostrar cuántos expedientes se pasaron de lo que la compañía se propuso."
          />
        </div>

        <label class="row">
          <app-switch [(checked)]="enabled" ariaLabel="Fijar un objetivo de resolución" />
          <span class="row-text">
            <span class="row-label">Medir contra un objetivo</span>
            <span class="t-note">
              Apagado, el tablero muestra el tiempo promedio sin compararlo con nada.
            </span>
          </span>
        </label>

        @if (enabled()) {
          <div class="field">
            <label class="t-field-label" for="objetivo-dias">Días</label>
            <app-input
              id="objetivo-dias"
              type="number"
              [(value)]="days"
              [min]="MIN"
              [max]="MAX_ATTR"
            />
            @if (invalid()) {
              <p class="error t-note">Tiene que ser un número entre {{ MIN }} y {{ MAX }}.</p>
            }
          </div>
        }

        <app-save-bar
          [dirty]="dirty()"
          [saving]="saving()"
          [error]="error()"
          [canSave]="!invalid()"
          (save)="save()"
          (discard)="discard()"
        />
      </app-card>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .head {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      margin-bottom: var(--space-4);
    }
    .card-title {
      margin: 0;
      font-size: var(--font-size-lg);
      font-weight: var(--font-weight-bold);
      color: var(--text-primary);
    }
    .row {
      display: flex;
      align-items: flex-start;
      gap: var(--space-3);
      cursor: pointer;
    }
    .row-text {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .row-label {
      font-size: var(--font-size-body);
      color: var(--text-primary);
    }
    .field {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      margin-top: var(--space-4);
      max-width: 160px;
    }
    .error {
      color: var(--status-danger);
    }
  `,
})
export class ObjetivoConfigComponent {
  private readonly service = inject(ResolutionTargetService);

  protected readonly MIN = TARGET_DAYS_MIN;
  protected readonly MAX = TARGET_DAYS_MAX;
  /** El input tipa `max` como string; el numérico queda para el mensaje de error. */
  protected readonly MAX_ATTR = String(TARGET_DAYS_MAX);

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly enabled = signal(false);
  /** Texto y no número: el input devuelve string y vacío tiene que ser distinguible de cero. */
  protected readonly days = signal('');

  /** Lo último confirmado por el backend, para saber qué cambió y para poder descartar. */
  private readonly saved = signal<ResolutionTarget>({ enabled: false, targetDays: null });

  protected readonly invalid = computed(() => {
    if (!this.enabled()) {
      return false;
    }
    const value = Number(this.days());
    return !Number.isInteger(value) || value < this.MIN || value > this.MAX;
  });

  protected readonly dirty = computed(() => {
    const saved = this.saved();
    const days = this.days() === '' ? null : Number(this.days());
    return saved.enabled !== this.enabled() || saved.targetDays !== days;
  });

  constructor() {
    this.load();
  }

  private load(): void {
    this.service.get().subscribe({
      next: (target) => {
        this.apply(target);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('No pudimos cargar el objetivo.');
        this.loading.set(false);
      },
    });
  }

  private apply(target: ResolutionTarget): void {
    this.saved.set(target);
    this.enabled.set(target.enabled);
    this.days.set(target.targetDays === null ? '' : String(target.targetDays));
  }

  protected save(): void {
    this.saving.set(true);
    this.error.set(null);
    this.service
      .save({
        enabled: this.enabled(),
        // Apagarlo no borra el número: si lo vuelven a encender, vuelve el que había.
        targetDays: this.days() === '' ? null : Number(this.days()),
      })
      .subscribe({
        next: (target) => {
          this.apply(target);
          this.saving.set(false);
        },
        error: (failure: HttpErrorResponse) => {
          this.error.set(failure.error?.detail ?? 'No pudimos guardar el objetivo.');
          this.saving.set(false);
        },
      });
  }

  protected discard(): void {
    this.apply(this.saved());
    this.error.set(null);
  }
}
