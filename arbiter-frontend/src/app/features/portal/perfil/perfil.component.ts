import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { ProfileService } from '../../../core/auth/profile.service';
import {
  IMAGE_CONSENT_DETAIL,
  IMAGE_CONSENT_SUMMARY,
  IMAGE_CONSENT_VERSION,
  InsuredProfile,
} from '../../../core/models/profile';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CheckboxComponent } from '../../../shared/ui/checkbox/checkbox.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { ToastService } from '../../../shared/ui/toast/toast.service';

type ProfileState =
  { status: 'loading' } | { status: 'ok'; profile: InsuredProfile } | { status: 'error' };

/**
 * Image consent must be revocable as easily as it was given (Ley 25.326). Revoking only affects
 * future claims: past classifications are an audit record and stay.
 */
@Component({
  selector: 'app-perfil',
  imports: [ButtonComponent, CheckboxComponent, InlineLoadingComponent, InputComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './perfil.component.html',
  styleUrl: './perfil.component.scss',
})
export class PerfilComponent {
  private readonly profileService = inject(ProfileService);
  private readonly toast = inject(ToastService);

  protected readonly consentSummary = IMAGE_CONSENT_SUMMARY;
  protected readonly consentDetail = IMAGE_CONSENT_DETAIL;

  // Bumped after saving: the PATCH returns a new token, not the profile, so it must be re-fetched.
  private readonly reload = signal(0);

  private readonly state = toSignal(
    toObservable(this.reload).pipe(
      switchMap(() =>
        this.profileService.get().pipe(
          map((profile): ProfileState => ({ status: 'ok', profile })),
          startWith<ProfileState>({ status: 'loading' }),
          catchError(() => of<ProfileState>({ status: 'error' })),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as ProfileState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly loadError = computed(() => this.state().status === 'error');

  protected readonly profile = computed<InsuredProfile | null>(() => {
    const state = this.state();
    return state.status === 'ok' ? state.profile : null;
  });

  protected readonly email = signal('');
  protected readonly phone = signal('');
  protected readonly imageConsent = signal(false);

  constructor() {
    // Runs only when a new profile object arrives, so it doesn't overwrite what's being typed.
    effect(() => {
      const profile = this.profile();
      if (!profile) {
        return;
      }
      this.email.set(profile.email ?? '');
      this.phone.set(profile.phone ?? '');
      this.imageConsent.set(profile.imageConsent);
    });
  }

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly emailValid = computed(() =>
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.email().trim()),
  );
  protected readonly phoneValid = computed(() => this.phone().trim().length >= 6);

  protected readonly dirty = computed(() => {
    const profile = this.profile();
    if (!profile) {
      return false;
    }
    return (
      this.email().trim() !== (profile.email ?? '') ||
      this.phone().trim() !== (profile.phone ?? '') ||
      this.imageConsent() !== profile.imageConsent
    );
  });

  protected readonly canSave = computed(
    () => this.dirty() && this.emailValid() && this.phoneValid() && !this.saving(),
  );

  protected readonly consentAt = computed(() => {
    const at = this.profile()?.imageConsentAt;
    return at ? new Date(at).toLocaleDateString('es-AR') : null;
  });

  protected save(): void {
    const profile = this.profile();
    if (!this.canSave() || !profile) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);

    const consentChanged = this.imageConsent() !== profile.imageConsent;

    this.profileService
      .update({
        email: this.email().trim(),
        phone: this.phone().trim(),
        imageConsent: this.imageConsent(),
        // Only when consent changed: sending it otherwise would reset the consent date.
        ...(consentChanged ? { imageConsentVersion: IMAGE_CONSENT_VERSION } : {}),
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.reload.update((n) => n + 1);
          this.toast.show('Tus datos se guardaron', 'ok');
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.saveError.set(this.messageFor(err));
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
    return 'No pudimos guardar los cambios. Probá de nuevo en unos minutos.';
  }
}
