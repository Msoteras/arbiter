import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { AuthService } from '../../../core/auth/auth.service';
import { homeRouteFor } from '../../../core/models/user-role';
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
  // sessionExpired: authInterceptor adds it after a 401 on any /api call — without it the user had
  // no way of knowing what happened was that the token expired.
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
        this.router.navigateByUrl(homeRouteFor(response.rol));
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        // The real detail goes to the console; the user sees a narrow message. Without this, a 500
        // and a downed backend were indistinguishable when diagnosing.
        console.error('Login failed', { status: err.status, detail: err.error });
        this.errorMessage.set(this.messageFor(err));
      },
    });
  }

  private messageFor(err: HttpErrorResponse): string {
    if (err.status === 401) {
      return err.error?.detail ?? 'Email o contraseña inválidos.';
    }
    if (err.status === 423) {
      return err.error?.detail ?? 'Cuenta bloqueada temporalmente. Probá de nuevo más tarde.';
    }
    // canSubmit already catches empty fields, so a 400 from the server means the sealed password
    // couldn't be opened: the backend rotated its key on restart, or the browser clock is off.
    if (err.status === 400) {
      return err.error?.detail ?? 'No pudimos procesar el pedido. Recargá la página y probá de nuevo.';
    }
    // status 0 = no response from the server: backend down, no connection, timeout or CORS.
    if (err.status === 0) {
      return 'No pudimos conectar con el servidor. Revisá tu conexión a internet; si el problema persiste, avisá a soporte.';
    }
    // 5xx: the backend answered with an error of its own — not a transient network problem.
    if (err.status >= 500) {
      return 'El servicio no está disponible por el momento. Ya estamos al tanto; probá de nuevo en unos minutos o avisá a soporte si sigue.';
    }
    return 'No se pudo iniciar sesión. Probá de nuevo en unos minutos.';
  }
}
