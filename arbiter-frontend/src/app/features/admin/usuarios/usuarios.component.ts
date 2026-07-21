import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { UserAdminService, UserResponse } from '../../../core/auth/user-admin.service';
import { UserRole, userRoleLabel } from '../../../core/models/user-role';
import { AltaUsuarioComponent } from '../alta-usuario/alta-usuario.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';

/**
 * Trello "Gestión de roles y permisos" - listado (GET) + selector de rol editable (PUT).
 * El referente no puede cambiar su propio rol (lo valida el backend; acá lo deshabilitamos
 * directamente para no dejarlo intentar). "+ Nuevo usuario" abre el alta en un panel (wireframe),
 * en vez de navegar a una página aparte.
 */
@Component({
  selector: 'app-usuarios',
  imports: [FormsModule, AltaUsuarioComponent, BadgeComponent, ButtonComponent, CardComponent, ModalComponent],
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
  protected readonly showCreatePanel = signal(false);

  protected readonly userToDelete = signal<UserResponse | null>(null);
  protected readonly deleting = signal(false);
  protected readonly deleteError = signal<string | null>(null);

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

  protected onCreated(user: UserResponse): void {
    this.users.update((list) => [user, ...list]);
    this.showCreatePanel.set(false);
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

  /** Abre la confirmación destructiva (wireframe: "Eliminar pide confirmación destructiva, doble verbo"). */
  protected requestDelete(user: UserResponse): void {
    this.deleteError.set(null);
    this.userToDelete.set(user);
  }

  protected cancelDelete(): void {
    this.userToDelete.set(null);
  }

  protected confirmDelete(): void {
    const user = this.userToDelete();
    if (!user) {
      return;
    }
    this.deleting.set(true);
    this.service.delete(user.id).subscribe({
      next: () => {
        this.deleting.set(false);
        this.userToDelete.set(null);
        this.users.update((list) => list.filter((u) => u.id !== user.id));
      },
      error: (err: HttpErrorResponse) => {
        this.deleting.set(false);
        this.deleteError.set(err.error?.detail ?? 'No se pudo eliminar el usuario. Probá de nuevo.');
      },
    });
  }
}
