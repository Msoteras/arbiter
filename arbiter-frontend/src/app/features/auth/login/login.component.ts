import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { AuthService } from '../../../core/auth/auth.service';
import { UserRole } from '../../../core/models/user-role';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { LogoComponent } from '../../../shared/ui/logo/logo.component';

@Component({
  selector: 'app-login',
  imports: [ButtonComponent, InputComponent, LogoComponent, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly authService = inject(AuthService);
  private readonly session = inject(AuthSessionService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly email = signal('');
  protected readonly password = signal('');
  protected readonly submitting = signal(false);
  // sessionExpired: lo agrega authInterceptor tras un 401 en cualquier llamada /api —
  // sin esto el usuario no tenía forma de saber que lo que pasó fue que venció el token.
  protected readonly errorMessage = signal<string | null>(
    this.route.snapshot.queryParamMap.has('sessionExpired')
      ? 'Tu sesión expiró. Ingresá de nuevo.'
      : null,
  );

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
        // El detalle real (status + payload) va a consola/observabilidad; el usuario ve un
        // mensaje acotado. Sin esto, un 500 y un backend caído eran indistinguibles a la hora
        // de diagnosticar, dependiendo del relato del usuario.
        console.error('Login falló', { status: err.status, detail: err.error });
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
    // status 0 = no hubo respuesta del servidor: backend caído, sin conexión, timeout o CORS.
    if (err.status === 0) {
      return 'No pudimos conectar con el servidor. Revisá tu conexión a internet; si el problema persiste, avisá a soporte.';
    }
    // 5xx: el backend respondió con un error propio — no es un problema transitorio de red.
    if (err.status >= 500) {
      return 'El servicio no está disponible por el momento. Ya estamos al tanto; probá de nuevo en unos minutos o avisá a soporte si sigue.';
    }
    return 'No se pudo iniciar sesión. Probá de nuevo en unos minutos.';
  }
}
