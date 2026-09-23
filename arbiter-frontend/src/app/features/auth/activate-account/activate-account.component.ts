import { Location } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { AuthService } from '../../../core/auth/auth.service';
import { homeRouteFor } from '../../../core/models/user-role';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { LogoComponent } from '../../../shared/ui/logo/logo.component';

type Mode = 'activate' | 'reset';

/**
 * "Choose your password" screen shared by account activation and password reset; the route's
 * `data.mode` picks which. The one-time token is validated (without consuming it) before the form
 * shows, and a successful submit returns a session, so the user lands straight in the app.
 */
@Component({
  selector: 'app-activate-account',
  imports: [ButtonComponent, CardComponent, InputComponent, InlineLoadingComponent, LogoComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './activate-account.component.html',
  styleUrl: './activate-account.component.scss',
})
export class ActivateAccountComponent implements OnInit {
  private readonly authService = inject(AuthService);
  private readonly session = inject(AuthSessionService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);

  protected readonly mode: Mode = (this.route.snapshot.data['mode'] as Mode) ?? 'activate';
  protected readonly token = this.route.snapshot.queryParamMap.get('token');

  protected readonly tokenStatus = signal<'checking' | 'valid' | 'invalid'>(
    this.token ? 'checking' : 'invalid',
  );

  protected readonly password = signal('');
  protected readonly confirmPassword = signal('');
  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly copy =
    this.mode === 'reset'
      ? {
          brandTitle: 'Restablecé tu contraseña',
          brandTag: 'Elegí una contraseña nueva para volver a entrar a Arbiter.',
          formTitle: 'Elegí tu contraseña nueva',
          formNote: 'Con esto quedás adentro, sin tener que volver a loguearte.',
          submitLabel: 'Restablecer contraseña',
          submittingLabel: 'Restableciendo…',
        }
      : {
          brandTitle: 'Activá tu cuenta',
          brandTag: 'Elegí tu contraseña para terminar de configurar tu acceso a Arbiter.',
          formTitle: 'Elegí tu contraseña',
          formNote:
            'Con esto activás tu cuenta y entrás directo, sin tener que loguearte de nuevo.',
          submitLabel: 'Activar cuenta',
          submittingLabel: 'Activando…',
        };

  /**
   * Mirrors Auth0's default ("Good") password policy: a password that fails it surfaces only as an
   * opaque 502 from the backend, so it's checked upfront.
   */
  protected readonly passwordRequirements = computed(() => {
    const pwd = this.password();
    return {
      minLength: pwd.length >= 8,
      variety: this.charClassCount(pwd) >= 3,
      noRepeats: pwd.length === 0 || !/(.)\1\1/.test(pwd),
    };
  });

  protected readonly passwordValid = computed(() => {
    const r = this.passwordRequirements();
    return r.minLength && r.variety && r.noRepeats;
  });

  protected readonly passwordsMatch = computed(
    () => this.password().length > 0 && this.password() === this.confirmPassword(),
  );

  protected readonly canSubmit = computed(
    () => this.tokenStatus() === 'valid' && this.passwordValid() && this.passwordsMatch(),
  );

  private charClassCount(pwd: string): number {
    let count = 0;
    if (/[a-z]/.test(pwd)) count++;
    if (/[A-Z]/.test(pwd)) count++;
    if (/[0-9]/.test(pwd)) count++;
    if (/[^a-zA-Z0-9]/.test(pwd)) count++;
    return count;
  }

  ngOnInit(): void {
    if (!this.token) {
      return;
    }
    // Strip the token from the address bar so it doesn't linger in history or screenshots.
    this.location.replaceState(this.location.path().split('?')[0]);

    this.authService.checkToken(this.token).subscribe({
      next: () => this.tokenStatus.set('valid'),
      error: () => this.tokenStatus.set('invalid'),
    });
  }

  protected submit(): void {
    if (!this.token || !this.canSubmit() || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.errorMessage.set(null);

    const request$ =
      this.mode === 'reset'
        ? this.authService.resetPassword(this.token, this.password())
        : this.authService.activate(this.token, this.password());

    request$.subscribe({
      // submitting stays true on purpose: resetting it would flash the form before navigating away.
      next: (response) => {
        this.session.start(response);
        this.router.navigateByUrl(homeRouteFor(response.rol));
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.errorMessage.set(this.messageFor(err));
      },
    });
  }

  private messageFor(err: HttpErrorResponse): string {
    const action = this.mode === 'reset' ? 'restablecer la contraseña' : 'activar la cuenta';
    if (err.status === 400) {
      return err.error?.detail ?? 'El link no es válido o venció.';
    }
    if (err.status === 502) {
      return `No se pudo ${action} — puede que la contraseña no cumpla los requisitos de seguridad. Probá con otra.`;
    }
    return `No se pudo ${action}. Probá de nuevo en unos minutos.`;
  }
}
