import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
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
import { InputComponent } from '../../../shared/ui/input/input.component';
import { LoadingComponent } from '../../../shared/ui/loading/loading.component';
import { ToastService } from '../../../shared/ui/toast/toast.service';

type ProfileState =
  | { status: 'loading' }
  | { status: 'ok'; profile: InsuredProfile }
  | { status: 'error' };

/**
 * "Mi perfil" del asegurado. La contracara del onboarding: lo que ahí se completa una vez, acá
 * se puede ver y cambiar en cualquier momento — sobre todo el consentimiento de imágenes, que
 * por ser libre (Ley 25.326) tiene que poder revocarse tan fácil como se dio.
 *
 * Revocar no borra nada de lo ya analizado: las clasificaciones hechas son registro auditable
 * (Disposición SSN 2/2023) y quedan. El cambio aplica a las denuncias siguientes.
 *
 * Nombre, DNI y PEP son solo lectura: salen de la póliza/KYC de la aseguradora, no de acá.
 */
@Component({
  selector: 'app-perfil',
  imports: [ButtonComponent, CheckboxComponent, InputComponent, LoadingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './perfil.component.html',
  styleUrl: './perfil.component.scss',
})
export class PerfilComponent {
  private readonly profileService = inject(ProfileService);
  private readonly toast = inject(ToastService);

  protected readonly consentSummary = IMAGE_CONSENT_SUMMARY;
  protected readonly consentDetail = IMAGE_CONSENT_DETAIL;

  // Se relee después de guardar: el PATCH devuelve un LoginResponse (token nuevo), no el perfil,
  // así que sin esto la fecha y la versión del consentimiento seguirían mostrando lo viejo.
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
    // Reaplica en cada llegada del perfil (carga inicial y recarga post-guardado). No pisa lo
    // que se está tipeando porque solo corre cuando cambia la identidad del objeto perfil.
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

  /** Solo se habilita si algo cambió: un "Guardar" que no guarda nada confunde. */
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

  /** Desde cuándo rige el consentimiento guardado. */
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
        // La versión viaja solo si el consentimiento cambió: es lo que se está aceptando (o
        // revocando) ahora. Mandarla en un cambio de teléfono reescribiría la fecha de un
        // consentimiento que nadie tocó.
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
