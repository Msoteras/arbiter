import { Location } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { AuthService } from '../../../core/auth/auth.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';

type Mode = 'activate' | 'reset';

/**
 * "Choose your password" screen, shared by two flows that arrive here from a one-time-token
 * mail link: account activation (Auth0 Phase 3 — UserService.createUser) and "forgot password"
 * (UserService.requestPasswordReset). The route (`data.mode`, see app.routes.ts) picks the mode,
 * which drives the copy and which endpoint the submit hits. Before showing the form it validates
 * the token against the backend (GET /invite-tokens/{token}) without consuming it, so a made-up
 * or expired token in the URL never gets to see the password form.
 */
@Component({
  selector: 'app-activate-account',
  imports: [ButtonComponent, CardComponent, InputComponent, InlineLoadingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './activate-account.component.html',
  styleUrl: './activate-account.component.scss',
})
export class ActivateAccountComponent implements OnInit {
  private readonly authService = inject(AuthService);
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
  protected readonly activated = signal(false);

  protected readonly copy =
    this.mode === 'reset'
      ? {
          brandTitle: 'Restablecé tu contraseña',
          brandTag: 'Elegí una contraseña nueva para volver a entrar a Arbiter.',
          formTitle: 'Elegí tu contraseña nueva',
          formNote: 'Con esto vas a poder iniciar sesión de nuevo en Arbiter.',
          submitLabel: 'Restablecer contraseña',
          submittingLabel: 'Restableciendo…',
          successTitle: '¡Listo!',
          successNote: 'Tu contraseña se actualizó. Ya podés iniciar sesión.',
        }
      : {
          brandTitle: 'Activá tu cuenta',
          brandTag: 'Elegí tu contraseña para terminar de configurar tu acceso a Arbiter.',
          formTitle: 'Elegí tu contraseña',
          formNote: 'Con esto activás tu cuenta y ya podés iniciar sesión en Arbiter.',
          submitLabel: 'Activar cuenta',
          submittingLabel: 'Activando…',
          successTitle: '¡Listo!',
          successNote: 'Tu cuenta ya está activada. Ya podés iniciar sesión con tu contraseña.',
        };

  /**
   * Mirrors Auth0's default ("Good") password policy: if it's not met, Auth0 rejects the
   * Management API call and the user gets a 502 with no idea why (see activateAccount/
   * resetPassword in UserService — the link is still valid to retry, but without this
   * upfront check the user would never know the password was the actual problem).
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
    // Already captured from the query param — strip it from the address bar so it doesn't
    // linger in browser history or end up in an accidental screenshot.
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
      next: () => {
        this.submitting.set(false);
        this.activated.set(true);
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.errorMessage.set(this.messageFor(err));
      },
    });
  }

  protected goToLogin(): void {
    this.router.navigateByUrl('/login');
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
