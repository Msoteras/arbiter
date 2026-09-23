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
 * Fraud record policy plus the expert firms catalog. The save bar only covers the policy: the catalog
 * persists instantly. Whether a record adds to the risk level is configured in scoring, not here.
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
  /** Last state confirmed by the backend; unsaved changes are measured against it. */
  private readonly persisted = signal<FraudRecordRule | null>(null);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Compares whole objects rather than tracking a flag, so reverting a value disables save again. */
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
  /** app-input works with strings. */
  protected readonly windowMaxAttr = String(WINDOW_MONTHS_MAX);
  protected readonly windowValue = computed(() => String(this.rule()?.windowMonths ?? ''));

  /** The field is in months (what's stored); the years equivalent is only a reading aid. */
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
    // Empty isn't zero (0 would expire every record at birth): keep the previous value and let save
    // validate.
    const months = Number(value);
    this.patch({ windowMonths: Number.isFinite(months) ? months : this.rule()!.windowMonths });
  }

  private patch(change: Partial<FraudRecordRule>): void {
    this.rule.update((current) => (current ? { ...current, ...change } : current));
    this.error.set(null);
  }

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
