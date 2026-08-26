import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
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
import { PolicyService } from '../../expedientes/policy.service';

type ProfileState =
  | { status: 'loading' }
  | { status: 'ok'; profile: InsuredProfile }
  | { status: 'error' };

type PoliciesState =
  | { status: 'loading' }
  | { status: 'ok'; policies: Policy[] }
  | { status: 'error' };

/**
 * H0009 — pantalla de bienvenida del asegurado (primer ingreso).
 *
 * Se ve UNA sola vez, después de elegir la contraseña y entrar por primera vez: acá el asegurado
 * confirma sus datos de contacto y da sus consentimientos, para no tener que declararlos de nuevo
 * en cada denuncia. `onboardingGuard` la impone; al completarla el backend devuelve un JWT con
 * `onboardingComplete: true` y deja de aparecer.
 *
 * Qué NO se pregunta acá:
 *  · La contraseña, que ya se eligió en ActivateAccountComponent. El orden importa: no se le
 *    piden datos personales a alguien que todavía no probó que el token de invitación es suyo.
 *  · PEP, que la aseguradora ya tiene de la póliza/KYC. Se muestra para que la persona sepa qué
 *    figura de ella, pero no se declara acá: no es un dato autodeclarado.
 *
 * El consentimiento de imágenes NO gatea "Continuar": tiene que ser LIBRE (Ley 25.326,
 * transferencia internacional de datos). Negarse no puede impedir usar el portal ni denunciar,
 * por eso el botón sigue habilitado con el checkbox en false.
 */
@Component({
  selector: 'app-onboarding',
  imports: [ButtonComponent, CheckboxComponent, InputComponent, LoadingComponent, LogoComponent],
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

  // ───────────────── Perfil: precarga del contacto que la aseguradora ya tiene ─────────────────
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
    // Un effect y no un valor inicial: el perfil llega después del primer render. Se destruye
    // apenas aplica, para no volver a pisar lo que la persona ya empezó a tipear.
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

  // ───────────────── Pólizas (solo lectura) ─────────────────
  // Para que vea de qué aseguradora es y qué tiene cubierto antes de arrancar.
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

  // ───────────────── Envío ─────────────────
  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);

  protected readonly emailValid = computed(() =>
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.email().trim()),
  );
  protected readonly phoneValid = computed(() => this.phone().trim().length >= 6);

  /** El consentimiento queda fuera a propósito: es libre, no un requisito para continuar. */
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
        // Se manda siempre, acepte o no: hay que poder reconstruir a qué texto dijo que sí (o
        // que no). Un consentimiento sin versión ni fecha no sirve como consentimiento.
        imageConsentVersion: IMAGE_CONSENT_VERSION,
      })
      .subscribe({
        // ProfileService ya reemplazó la sesión con el JWT nuevo (onboardingComplete: true),
        // así que para cuando navegamos el guard deja pasar.
        next: () => this.router.navigateByUrl('/portal/home'),
        error: (err: HttpErrorResponse) => {
          this.submitting.set(false);
          // 409 = ya estaba completo (doble submit, o dos pestañas abiertas). No es un error
          // para el usuario: el estado deseado ya se cumplió, seguimos al portal.
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

  protected vigencia(policy: Policy): string {
    const desde = new Date(policy.effectiveFrom).toLocaleDateString('es-AR');
    const hasta = new Date(policy.effectiveTo).toLocaleDateString('es-AR');
    return `${desde} – ${hasta}`;
  }
}
