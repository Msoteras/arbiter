import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import {
  FraudRecordRule,
  FraudRuleService,
  WINDOW_MONTHS_MAX,
  WINDOW_MONTHS_MIN,
} from '../fraud-rule.service';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { SaveBarComponent } from '../../../shared/ui/save-bar/save-bar.component';
import { SwitchComponent } from '../../../shared/ui/switch/switch.component';

import { fadeInUp } from '../../../shared/animations';
import { ONBOARDING_STEP_LINE } from '../../../core/onboarding/onboarding';
import { OnboardingTargetDirective } from '../../../core/onboarding/onboarding-target.directive';
import { MonthsInputComponent } from '../rules/months-input.component';

/**
 * Fraud record policy. Whether a record adds to the risk level is configured in scoring, not here.
 */
@Component({
  selector: 'app-fraud-config',
  imports: [
    OnboardingTargetDirective,
    CardComponent,
    InfoTipComponent,
    MonthsInputComponent,
    InlineLoadingComponent,
    SaveBarComponent,
    SwitchComponent,
  ],
  animations: [fadeInUp],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './fraud-config.component.html',
  styleUrl: './fraud-config.component.scss',
})
export class FraudConfigComponent {
  protected readonly stepLine = ONBOARDING_STEP_LINE;

  private readonly service = inject(FraudRuleService);

  protected readonly rule = signal<FraudRecordRule | null>(null);
  /** Last state confirmed by the backend; unsaved changes are measured against it. */
  private readonly persisted = signal<FraudRecordRule | null>(null);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * Compares whole objects rather than tracking a flag, so reverting a value disables save again.
   * A policy never saved (`ruleId` null) shows the default and counts as unsaved: otherwise a
   * referent who agrees with the default has no way to save it.
   */
  protected readonly dirty = computed(() => {
    const current = this.rule();
    const saved = this.persisted();
    return (
      current != null &&
      saved != null &&
      (saved.ruleId == null ||
        current.windowMonths !== saved.windowMonths ||
        current.blocksFastTrack !== saved.blocksFastTrack)
    );
  });

  protected readonly windowMax = WINDOW_MONTHS_MAX;

  protected readonly windowInvalid = computed(() => {
    const months = this.rule()?.windowMonths;
    return (
      months == null ||
      !Number.isInteger(months) ||
      months < WINDOW_MONTHS_MIN ||
      months > WINDOW_MONTHS_MAX
    );
  });

  constructor() {
    this.reload();
  }

  private reload(): void {
    this.loading.set(true);
    // With unsaved changes a refresh stays in the cache; discarding picks it up (L1).
    this.service.get({ apply: () => !this.dirty() }).subscribe({
      next: (rule) => {
        this.rule.set({ ...rule });
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

  /** An empty amount arrives as null and is stored as NaN: `windowInvalid` blocks the save. */
  protected setWindow(months: number | null): void {
    this.patch({ windowMonths: months ?? Number.NaN });
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
    this.reload();
  }

  protected save(): void {
    const rule = this.rule();
    if (!rule || this.windowInvalid()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.service.save(rule).subscribe({
      next: (saved) => {
        this.rule.set({ ...saved });
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
