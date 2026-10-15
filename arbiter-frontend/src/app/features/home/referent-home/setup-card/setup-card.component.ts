import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { OnboardingService } from '../../../../core/onboarding/onboarding.service';
import {
  ONBOARDING_LEVEL_LABEL,
  ONBOARDING_STEP_LINE,
  OnboardingLevel,
  OnboardingStep,
  onboardingStepKey,
  onboardingStepSummary,
  onboardingStepTitle,
} from '../../../../core/onboarding/onboarding';
import { ButtonComponent } from '../../../../shared/ui/button/button.component';
import { InlineLoadingComponent } from '../../../../shared/ui/inline-loading/inline-loading.component';
import { ModalComponent } from '../../../../shared/ui/modal/modal.component';
import { ToastService } from '../../../../shared/ui/toast/toast.service';

interface StepGroup {
  level: OnboardingLevel;
  label: string;
  pending: OnboardingStep[];
  completed: OnboardingStep[];
}

const LEVELS: OnboardingLevel[] = ['REQUIRED', 'RECOMMENDED', 'OPTIONAL'];

/**
 * "Puesta en marcha" on the referent's home: the title, the count and the bar, with the checklist
 * in a side panel. Gone when nothing is pending.
 */
@Component({
  selector: 'app-setup-card',
  imports: [RouterLink, ButtonComponent, InlineLoadingComponent, ModalComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './setup-card.component.html',
  styleUrl: './setup-card.component.scss',
})
export class SetupCardComponent {
  private readonly onboarding = inject(OnboardingService);
  private readonly toast = inject(ToastService);

  protected readonly steps = this.onboarding.steps;
  protected readonly failed = this.onboarding.failed;

  private readonly anyPending = computed(() =>
    (this.steps() ?? []).some((s) => s.status === 'PENDING'),
  );

  /** Gone once nothing is pending; loading and errors show, there is no telling yet. */
  protected readonly visible = computed(
    () => this.failed() || this.steps() === null || this.anyPending(),
  );

  protected readonly panelOpen = signal(false);

  // Shown from the start: in the panel there is room, and they are the progress already made.
  protected readonly showCompleted = signal(true);
  protected readonly optionalOpen = signal(false);
  protected readonly busyKey = signal<string | null>(null);

  protected readonly total = computed(() => this.steps()?.length ?? 0);
  protected readonly done = computed(
    () => (this.steps() ?? []).filter((s) => s.status !== 'PENDING').length,
  );
  protected readonly progressPct = computed(() =>
    this.total() === 0 ? 0 : Math.round((this.done() / this.total()) * 100),
  );

  protected readonly groups = computed<StepGroup[]>(() =>
    LEVELS.map((level) => {
      const inLevel = (this.steps() ?? []).filter((s) => s.level === level);
      return {
        level,
        label: ONBOARDING_LEVEL_LABEL[level],
        pending: inLevel.filter((s) => s.status === 'PENDING'),
        completed: inLevel.filter((s) => s.status !== 'PENDING'),
      };
    }).filter((g) => g.pending.length + g.completed.length > 0),
  );

  /** A group with nothing to show would leave its heading alone. */
  protected readonly visibleGroups = computed(() =>
    this.groups().filter(
      (g) => g.pending.length > 0 || (this.showCompleted() && g.completed.length > 0),
    ),
  );

  protected readonly title = onboardingStepTitle;
  protected readonly summary = onboardingStepSummary;
  protected readonly key = onboardingStepKey;

  protected line(step: OnboardingStep): string {
    return ONBOARDING_STEP_LINE[step.step];
  }

  protected route(step: OnboardingStep) {
    return this.onboarding.routeOf(step);
  }

  /** The panel closes on the way: the step's screen is where the referente goes to work. */
  protected goTo(step: OnboardingStep): void {
    this.onboarding.requestFocus(step.step);
    this.panelOpen.set(false);
  }

  protected groupOpen(group: StepGroup): boolean {
    return group.level !== 'OPTIONAL' || this.optionalOpen();
  }

  protected retry(): void {
    this.onboarding.load();
  }

  protected setSkipped(step: OnboardingStep, skipped: boolean): void {
    this.busyKey.set(this.key(step));
    this.onboarding.setSkipped(step.step, skipped).subscribe({
      next: () => this.busyKey.set(null),
      error: () => {
        this.busyKey.set(null);
        this.toast.show('No se pudo guardar el cambio.', 'danger');
      },
    });
  }
}
