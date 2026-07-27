import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { AuthService } from '../../../core/auth/auth.service';
import { UserRole } from '../../../core/models/user-role';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { LogoComponent } from '../../../shared/ui/logo/logo.component';

@Component({
  selector: 'app-login',
  imports: [ButtonComponent, CardComponent, InputComponent, LogoComponent, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly authService = inject(AuthService);
  private readonly session = inject(AuthSessionService);
  private readonly router = inject(Router);

  protected readonly email = signal('');
  protected readonly password = signal('');
  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly canSubmit = computed(
    () => this.email().trim().length > 0 && this.password().length > 0,
  );

  protected submit(): void {
    if (!this.canSubmit() || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.errorMessage.set(null);

    this.authService.login({ email: this.email().trim(), password: this.password() }).subscribe({
      next: (response) => {
        this.session.start(response);
        this.router.navigateByUrl(this.homeFor(response.rol));
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.errorMessage.set(this.messageFor(err));
      },
    });
  }

  /** El home de cada rol es su propia sección — el referente ya no aterriza en la bandeja del analista. */
  private homeFor(rol: UserRole): string {
    if (rol === 'ASEGURADO') return '/portal';
    if (rol === 'REFERENTE_ASEGURADORA') return '/insurer/users';
    return '/inbox';
  }

  private messageFor(err: HttpErrorResponse): string {
    if (err.status === 401) {
      return err.error?.detail ?? 'Email o contraseña inválidos.';
    }
    if (err.status === 423) {
      return err.error?.detail ?? 'Cuenta bloqueada temporalmente. Probá de nuevo más tarde.';
    }
    if (err.status === 400) {
      return 'Completá email y contraseña.';
    }
    return 'No se pudo iniciar sesión. Probá de nuevo en unos minutos.';
  }
}
