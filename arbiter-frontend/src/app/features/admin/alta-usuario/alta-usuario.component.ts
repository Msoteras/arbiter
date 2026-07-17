import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { CreateUserRequest, UserAdminService, UserResponse } from '../../../core/auth/user-admin.service';

/**
 * H0002 - Alta de Usuarios. Por ahora solo crea cuentas ANALISTA_SINIESTROS (ver CLAUDE.md,
 * decisión #8) — el selector de rol no está porque hoy hay un solo valor válido.
 */
@Component({
  selector: 'app-alta-usuario',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './alta-usuario.component.html',
  styleUrl: './alta-usuario.component.scss',
})
export class AltaUsuarioComponent {
  private readonly userAdminService = inject(UserAdminService);

  protected readonly email = signal('');
  protected readonly nombre = signal('');
  protected readonly apellido = signal('');
  protected readonly password = signal('');
  protected readonly sector = signal('');
  protected readonly fechaIngreso = signal('');

  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly created = signal<UserResponse | null>(null);

  protected readonly canSubmit = computed(
    () =>
      this.email().trim().length > 0 &&
      this.nombre().trim().length > 0 &&
      this.apellido().trim().length > 0 &&
      this.password().length > 0 &&
      this.sector().trim().length > 0,
  );

  protected submit(): void {
    if (!this.canSubmit() || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.errorMessage.set(null);
    this.created.set(null);

    const request: CreateUserRequest = {
      email: this.email().trim(),
      nombre: this.nombre().trim(),
      apellido: this.apellido().trim(),
      password: this.password(),
      rol: 'ANALISTA_SINIESTROS',
      sector: this.sector().trim(),
      fechaIngreso: this.fechaIngreso() || undefined,
    };

    this.userAdminService.create(request).subscribe({
      next: (response) => {
        this.submitting.set(false);
        this.created.set(response);
        this.resetForm();
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.errorMessage.set(this.messageFor(err));
      },
    });
  }

  private resetForm(): void {
    this.email.set('');
    this.nombre.set('');
    this.apellido.set('');
    this.password.set('');
    this.sector.set('');
    this.fechaIngreso.set('');
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
