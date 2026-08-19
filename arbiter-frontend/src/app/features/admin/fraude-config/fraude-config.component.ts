import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import {
  FraudRecordRule,
  FraudRuleService,
  WINDOW_MONTHS_MAX,
  WINDOW_MONTHS_MIN,
} from '../fraude-rule.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';

/**
 * Política de antecedentes de fraude de la aseguradora. Como el scoring y los peritos, es config
 * de TODA la aseguradora y no de un ramo: el antecedente pesa sobre la persona, y con qué cobertura
 * denunció la próxima vez no cambia lo que se determinó sobre ella.
 *
 * Son dos decisiones, y las dos son de la compañía y no nuestras:
 * cuánto tiempo un fraude comprobado sigue contando, y si además le saca la vía rápida a la
 * denuncia siguiente. Apagada, los antecedentes se siguen registrando y viendo — lo único que no
 * pasa es que cuenten.
 */
@Component({
  selector: 'app-fraude-config',
  imports: [ButtonComponent, CardComponent, InputComponent, InfoTipComponent, InlineLoadingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './fraude-config.component.html',
  styleUrl: './fraude-config.component.scss',
})
export class FraudeConfigComponent {
  private readonly service = inject(FraudRuleService);

  protected readonly rule = signal<FraudRecordRule | null>(null);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<string | null>(null);

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
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(err.error?.detail || 'No se pudo cargar la política de antecedentes');
      },
    });
  }

  protected toggleEnabled(): void {
    this.patch({ enabled: !this.rule()!.enabled });
  }

  protected toggleBlocksFastTrack(): void {
    this.patch({ blocksFastTrack: !this.rule()!.blocksFastTrack });
  }

  protected setWindow(value: string): void {
    // Vacío no es cero: dejar 0 haría que todo antecedente naciera vencido. Se queda en lo que
    // había y el guardado avisa si el número no sirve.
    const months = Number(value);
    this.patch({ windowMonths: Number.isFinite(months) ? months : this.rule()!.windowMonths });
  }

  private patch(change: Partial<FraudRecordRule>): void {
    this.rule.update((current) => (current ? { ...current, ...change } : current));
    this.saved.set(false);
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
        this.saving.set(false);
        this.saved.set(true);
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.error.set(err.error?.detail || 'No se pudo guardar la política de antecedentes');
      },
    });
  }
}
