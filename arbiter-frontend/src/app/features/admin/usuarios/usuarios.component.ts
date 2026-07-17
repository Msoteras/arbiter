import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { UserAdminService, UserResponse } from '../../../core/auth/user-admin.service';
import { UserRole, userRoleLabel } from '../../../core/models/user-role';

/**
 * Trello "Gestión de roles y permisos" - listado (GET) + selector de rol editable (PUT).
 * El referente no puede cambiar su propio rol (lo valida el backend; acá lo deshabilitamos
 * directamente para no dejarlo intentar).
 */
@Component({
  selector: 'app-usuarios',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './usuarios.component.html',
  styleUrl: './usuarios.component.scss',
})
export class UsuariosComponent {
  private readonly service = inject(UserAdminService);
  private readonly session = inject(AuthSessionService);

  protected readonly roles: UserRole[] = ['ASEGURADO', 'ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA'];

  protected readonly loading = signal(true);
  protected readonly hasError = signal(false);
  protected readonly users = signal<UserResponse[]>([]);
  protected readonly savingId = signal<number | null>(null);
  protected readonly roleError = signal<string | null>(null);

  protected readonly isEmpty = computed(() => !this.loading() && !this.hasError() && this.users().length === 0);

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.hasError.set(false);
    this.service.list().subscribe({
      next: (data) => {
        this.users.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.hasError.set(true);
        this.loading.set(false);
      },
    });
  }

  protected isSelf(user: UserResponse): boolean {
    return user.email === this.session.session()?.email;
  }

  protected roleLabel(rol: string): string {
    return userRoleLabel(rol);
  }

  protected onRoleChange(user: UserResponse, newRole: UserRole): void {
    if (newRole === user.rol) {
      return;
    }
    this.roleError.set(null);
    this.savingId.set(user.id);

    this.service.updateRole(user.id, newRole).subscribe({
      next: (updated) => {
        this.savingId.set(null);
        this.users.update((list) => list.map((u) => (u.id === updated.id ? updated : u)));
      },
      error: (err: HttpErrorResponse) => {
        this.savingId.set(null);
        this.roleError.set(err.error?.detail ?? 'No se pudo cambiar el rol. Probá de nuevo.');
      },
    });
  }
}
