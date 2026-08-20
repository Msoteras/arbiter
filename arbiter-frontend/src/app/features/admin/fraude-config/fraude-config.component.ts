import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import {
  FraudRecordRule,
  FraudRuleService,
  WINDOW_MONTHS_MAX,
  WINDOW_MONTHS_MIN,
} from '../fraude-rule.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { SaveBarComponent } from '../../../shared/ui/save-bar/save-bar.component';
import { SwitchComponent } from '../../../shared/ui/switch/switch.component';
import { PeritosConfigComponent } from '../peritos-config/peritos-config.component';

/**
 * Gestión de fraude de la aseguradora: el antecedente de reincidencia y el catálogo de peritos.
 *
 * Las dos cosas viven juntas porque contestan la misma pregunta —qué hace la compañía frente a un
 * fraude— y porque se usan en el mismo momento: el peritaje es lo que convierte una sospecha en el
 * hecho verificado del que después sale el antecedente.
 *
 * La barra de guardado gobierna solo la regla: el catálogo de peritos persiste al instante (alta,
 * edición y baja pegan directo contra el backend), así que para esa parte no hay nada pendiente que
 * guardar.
 *
 * Política de antecedentes de fraude de la aseguradora. Como el scoring y los peritos, es config
 * de TODA la aseguradora y no de un ramo: el antecedente pesa sobre la persona, y con qué cobertura
 * denunció la próxima vez no cambia lo que se determinó sobre ella.
 *
 * Son dos decisiones, y las dos son de la compañía y no nuestras: cuánto tiempo un fraude
 * comprobado sigue contando, y si le saca la vía rápida a la denuncia siguiente.
 *
 * Si el antecedente suma al nivel de riesgo —y cuánto— NO se decide acá sino en el scoring, con el
 * resto de los factores. Un segundo interruptor para lo mismo dejaba que esta pantalla dijera que
 * el antecedente puntúa mientras el scoring ni siquiera tenía el factor cargado.
 */
@Component({
  selector: 'app-fraude-config',
  imports: [
    CardComponent,
    InputComponent,
    InfoTipComponent,
    InlineLoadingComponent,
    SaveBarComponent,
    SwitchComponent,
    PeritosConfigComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './fraude-config.component.html',
  styleUrl: './fraude-config.component.scss',
})
export class FraudeConfigComponent {
  private readonly service = inject(FraudRuleService);

  protected readonly rule = signal<FraudRecordRule | null>(null);
  /** Lo último que confirmó el backend. Es contra esto que se decide si hay cambios sin guardar. */
  private readonly persisted = signal<FraudRecordRule | null>(null);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * Hay algo distinto de lo último guardado. Se compara el objeto entero y no se lleva un flag a
   * mano: con un flag, volver un valor a como estaba dejaba el botón habilitado igual, ofreciendo
   * guardar un cambio que no existe.
   */
  protected readonly dirty = computed(() => {
    const current = this.rule();
    const saved = this.persisted();
    return (
      current != null &&
      saved != null &&
      (current.windowMonths !== saved.windowMonths ||
        current.blocksFastTrack !== saved.blocksFastTrack)
    );
  });

  protected readonly windowMin = WINDOW_MONTHS_MIN;
  protected readonly windowMax = WINDOW_MONTHS_MAX;
  /** app-input trabaja con strings; la conversión se hace acá y no en la plantilla. */
  protected readonly windowMaxAttr = String(WINDOW_MONTHS_MAX);
  protected readonly windowValue = computed(() => String(this.rule()?.windowMonths ?? ''));

  /**
   * La ventana se guarda en meses, así que el campo dice meses. El equivalente en años va al lado
   * porque "36" es más difícil de leer de un vistazo que "3 años", y porque un campo rotulado en
   * años que guarda meses es la forma más fácil de que alguien escriba 3 queriendo decir 36.
   */
  protected readonly windowEquivalente = computed(() => {
    const months = this.rule()?.windowMonths ?? 0;
    if (months < 12) {
      return months === 1 ? '1 mes' : `${months} meses`;
    }
    const years = Math.floor(months / 12);
    const rest = months % 12;
    const yearsLabel = years === 1 ? '1 año' : `${years} años`;
    if (rest === 0) {
      return yearsLabel;
    }
    return `${yearsLabel} y ${rest === 1 ? '1 mes' : `${rest} meses`}`;
  });

  protected readonly windowInvalida = computed(() => {
    const months = this.rule()?.windowMonths;
    return months == null || months < WINDOW_MONTHS_MIN || months > WINDOW_MONTHS_MAX;
  });

  constructor() {
    this.reload();
  }

  private reload(): void {
    this.loading.set(true);
    this.service.get().subscribe({
      next: (rule) => {
        this.rule.set(rule);
        this.persisted.set({ ...rule });
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(err.error?.detail || 'No se pudo cargar la política de antecedentes');
      },
    });
  }

  protected setBlocksFastTrack(blocksFastTrack: boolean): void {
    this.patch({ blocksFastTrack });
  }


  protected setWindow(value: string): void {
    // Vacío no es cero: dejar 0 haría que todo antecedente naciera vencido. Se queda en lo que
    // había y el guardado avisa si el número no sirve.
    const months = Number(value);
    this.patch({ windowMonths: Number.isFinite(months) ? months : this.rule()!.windowMonths });
  }

  private patch(change: Partial<FraudRecordRule>): void {
    this.rule.update((current) => (current ? { ...current, ...change } : current));
    this.error.set(null);
  }

  /** Vuelve a lo último guardado. Sin esto, deshacer un cambio implicaba recargar la pantalla. */
  protected discard(): void {
    const saved = this.persisted();
    if (saved) {
      this.rule.set({ ...saved });
    }
    this.error.set(null);
  }

  protected save(): void {
    const rule = this.rule();
    if (!rule || this.windowInvalida()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.service.save(rule).subscribe({
      next: (saved) => {
        this.rule.set(saved);
        this.persisted.set({ ...saved });
        this.saving.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.error.set(err.error?.detail || 'No se pudo guardar la política de antecedentes');
      },
    });
  }
}
