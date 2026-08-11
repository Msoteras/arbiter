import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';

import { CreateUserRequest, UserAdminService, UserResponse } from '../../../core/auth/user-admin.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';

/**
 * H0002 - Alta de Usuarios. Por ahora solo crea cuentas ANALISTA_SINIESTROS (ver CLAUDE.md,
 * decisión #8) — el selector de rol no está porque hoy hay un solo valor válido.
 * Auth0 Phase 3: the referente no longer sets a password — the user is left "pending" and
 * gets an email to choose their own (see ActivateAccountComponent).
 * Vive dentro de un app-modal abierto desde UsuariosComponent (patrón del wireframe).
 */
@Component({
  selector: 'app-alta-usuario',
  imports: [ButtonComponent, InputComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './alta-usuario.component.html',
  styleUrl: './alta-usuario.component.scss',
})
export class AltaUsuarioComponent {
  private readonly userAdminService = inject(UserAdminService);

  readonly created = output<UserResponse>();
  readonly cancel = output<void>();

  protected readonly email = signal('');
  protected readonly nombre = signal('');
  protected readonly apellido = signal('');

  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  // sector / fechaIngreso se sacaron: el backend multi-tenant no los modela (el DER de usuarios ya
  // no tiene esas columnas) y se descartaban en silencio. Si algún día se quieren, va con una
  // historia de schema (columna en el perfil por rol), no como campo de UI suelto.

  protected readonly canSubmit = computed(
    () =>
      this.email().trim().length > 0 &&
      this.nombre().trim().length > 0 &&
      this.apellido().trim().length > 0,
  );

  protected submit(): void {
    if (!this.canSubmit() || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.errorMessage.set(null);

    const request: CreateUserRequest = {
      email: this.email().trim(),
      nombre: this.nombre().trim(),
      apellido: this.apellido().trim(),
      rol: 'ANALISTA_SINIESTROS',
    };

    this.userAdminService.create(request).subscribe({
      next: (response) => {
        this.submitting.set(false);
        this.created.emit(response);
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.errorMessage.set(this.messageFor(err));
      },
    });
  }

  private messageFor(err: HttpErrorResponse): string {
    if (err.status === 409) {
      return err.error?.detail ?? 'Ya existe un usuario con ese email.';
    }
    if (err.status === 400) {
      return err.error?.detail ?? 'Revisá los datos del formulario.';
    }
    if (err.status === 403) {
      return 'No tenés permisos para dar de alta usuarios.';
    }
    return 'No se pudo crear el usuario. Probá de nuevo en unos minutos.';
  }
}
