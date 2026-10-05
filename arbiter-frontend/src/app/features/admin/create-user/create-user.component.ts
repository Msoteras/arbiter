import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  output,
  signal,
} from '@angular/core';

import {
  CreateUserRequest,
  UserAdminService,
  UserResponse,
} from '../../../core/auth/user-admin.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { InputComponent } from '../../../shared/ui/input/input.component';

/**
 * Creates ANALISTA_SINIESTROS accounts only; there's no role selector because the backend rejects any
 * other role. Insured users are bulk-provisioned from the insurer's database instead. No password is
 * set here: the user gets an email to choose their own.
 */
@Component({
  selector: 'app-create-user',
  imports: [ButtonComponent, InputComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './create-user.component.html',
  styleUrl: './create-user.component.scss',
})
export class CreateUserComponent {
  private readonly userAdminService = inject(UserAdminService);

  readonly created = output<UserResponse>();
  readonly cancel = output<void>();

  protected readonly email = signal('');
  protected readonly firstName = signal('');
  protected readonly lastName = signal('');

  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly canSubmit = computed(
    () =>
      this.email().trim().length > 0 &&
      this.firstName().trim().length > 0 &&
      this.lastName().trim().length > 0,
  );

  protected submit(): void {
    if (!this.canSubmit() || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.errorMessage.set(null);

    const request: CreateUserRequest = {
      email: this.email().trim(),
      nombre: this.firstName().trim(),
      apellido: this.lastName().trim(),
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
