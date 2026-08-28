import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../../core/auth/auth.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { LogoComponent } from '../../../shared/ui/logo/logo.component';

/**
 * Asks for the email and triggers UserService.requestPasswordReset, which always responds 204
 * whether or not the email exists (no leaking which addresses are registered) — that's why this
 * shows the same confirmation message no matter what, save for a real network/server error.
 */
@Component({
  selector: 'app-forgot-password',
  imports: [ButtonComponent, InputComponent, LogoComponent, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './forgot-password.component.html',
  styleUrl: './forgot-password.component.scss',
})
export class ForgotPasswordComponent {
  private readonly authService = inject(AuthService);

  protected readonly email = signal('');
  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly sent = signal(false);

  protected readonly canSubmit = computed(() => this.email().trim().length > 0);

  protected submit(): void {
    if (!this.canSubmit() || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.errorMessage.set(null);

    this.authService.forgotPassword(this.email().trim()).subscribe({
      next: () => {
        this.submitting.set(false);
        this.sent.set(true);
      },
      error: (_err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.errorMessage.set('No se pudo procesar el pedido. Probá de nuevo en unos minutos.');
      },
    });
  }
}
