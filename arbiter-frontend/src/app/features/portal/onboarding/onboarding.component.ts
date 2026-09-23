import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { ProfileService } from '../../../core/auth/profile.service';
import { Policy } from '../../../core/models/policy';
import {
  IMAGE_CONSENT_DETAIL,
  IMAGE_CONSENT_SUMMARY,
  IMAGE_CONSENT_VERSION,
  InsuredProfile,
} from '../../../core/models/profile';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CheckboxComponent } from '../../../shared/ui/checkbox/checkbox.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { LoadingComponent } from '../../../shared/ui/loading/loading.component';
import { LogoComponent } from '../../../shared/ui/logo/logo.component';
import { PolicyCardComponent } from '../../../shared/ui/policy-card/policy-card.component';
import { PolicyService } from '../../expedientes/policy.service';

type ProfileState =
  { status: 'loading' } | { status: 'ok'; profile: InsuredProfile } | { status: 'error' };

type PoliciesState =
  { status: 'loading' } | { status: 'ok'; policies: Policy[] } | { status: 'error' };

/**
 * Insured's first-login screen, enforced by `onboardingGuard` until the backend issues a JWT with
 * `onboardingComplete: true`. PEP comes from the insurer's KYC and is shown, never self-declared.
 */
@Component({
  selector: 'app-onboarding',
  imports: [
    ButtonComponent,
    CheckboxComponent,
    InputComponent,
    LoadingComponent,
    LogoComponent,
    PolicyCardComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './onboarding.component.html',
  styleUrl: './onboarding.component.scss',
})
export class OnboardingComponent {
  private readonly profileService = inject(ProfileService);
  private readonly policyService = inject(PolicyService);
  private readonly session = inject(AuthSessionService);
  private readonly router = inject(Router);

  protected readonly consentSummary = IMAGE_CONSENT_SUMMARY;
  protected readonly consentDetail = IMAGE_CONSENT_DETAIL;

  protected readonly nombre = computed(() => this.session.session()?.nombre ?? '');

  private readonly profileState = toSignal(
    this.profileService.get().pipe(
      map((profile): ProfileState => ({ status: 'ok', profile })),
      startWith<ProfileState>({ status: 'loading' }),
      catchError(() => of<ProfileState>({ status: 'error' })),
    ),
    { initialValue: { status: 'loading' } as ProfileState },
  );

  protected readonly loading = computed(() => this.profileState().status === 'loading');
  protected readonly loadError = computed(() => this.profileState().status === 'error');

  private readonly profile = computed<InsuredProfile | null>(() => {
    const state = this.profileState();
    return state.status === 'ok' ? state.profile : null;
  });

  protected readonly pep = computed(() => this.profile()?.pep ?? false);
  protected readonly dni = computed(() => this.profile()?.dni ?? '');

  protected readonly email = signal('');
  protected readonly phone = signal('');
  protected readonly imageConsent = signal(false);

  constructor() {
    // One-shot prefill: destroyed once applied so it never overwrites what the user typed.
    const prefill = effect(() => {
      const profile = this.profile();
      if (!profile) {
        return;
      }
      this.email.set(profile.email ?? '');
      this.phone.set(profile.phone ?? '');
      this.imageConsent.set(profile.imageConsent);
      prefill.destroy();
    });
  }

  private readonly policiesState = toSignal(
    this.policyService.listByInsured(this.session.session()?.insuredId ?? '').pipe(
      map((policies): PoliciesState => ({ status: 'ok', policies })),
      startWith<PoliciesState>({ status: 'loading' }),
      catchError(() => of<PoliciesState>({ status: 'error' })),
    ),
    { initialValue: { status: 'loading' } as PoliciesState },
  );

  protected readonly policies = computed<Policy[]>(() => {
    const state = this.policiesState();
    return state.status === 'ok' ? state.policies : [];
  });
  protected readonly policiesLoading = computed(() => this.policiesState().status === 'loading');

  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);

  protected readonly emailValid = computed(() =>
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.email().trim()),
  );
  protected readonly phoneValid = computed(() => this.phone().trim().length >= 6);

  /**
   * Image consent is deliberately left out: under Ley 25.326 it must be freely given, so declining
   * can't block the portal.
   */
  protected readonly canSubmit = computed(
    () => this.emailValid() && this.phoneValid() && !this.submitting(),
  );

  protected submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.submitting.set(true);
    this.submitError.set(null);

    this.profileService
      .completeOnboarding({
        email: this.email().trim(),
        phone: this.phone().trim(),
        imageConsent: this.imageConsent(),
        // Always sent, accepted or not: the consent must be traceable to the exact text shown.
        imageConsentVersion: IMAGE_CONSENT_VERSION,
      })
      .subscribe({
        // ProfileService already swapped in the new JWT, so the guard lets us through.
        next: () => this.router.navigateByUrl('/portal/home'),
        error: (err: HttpErrorResponse) => {
          this.submitting.set(false);
          // 409: already completed (double submit or another tab), so just move on.
          if (err.status === 409) {
            this.router.navigateByUrl('/portal/home');
            return;
          }
          this.submitError.set(this.messageFor(err));
        },
      });
  }

  private messageFor(err: HttpErrorResponse): string {
    if (err.status === 400) {
      return err.error?.detail ?? 'Revisá los datos: alguno no tiene el formato esperado.';
    }
    if (err.status === 0) {
      return 'No pudimos conectar con el servidor. Revisá tu conexión e intentá de nuevo.';
    }
    return 'No pudimos guardar tus datos. Probá de nuevo en unos minutos.';
  }
}
