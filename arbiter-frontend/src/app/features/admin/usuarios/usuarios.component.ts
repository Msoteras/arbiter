import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { UserAdminService, UserResponse } from '../../../core/auth/user-admin.service';
import { UserRole, userRoleLabel } from '../../../core/models/user-role';
import { userStatusLabel, userStatusTone } from '../../../core/models/user-status';
import { AltaUsuarioComponent } from '../alta-usuario/alta-usuario.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { TableComponent } from '../../../shared/ui/table/table.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { fadeStagger, staggerReveal } from '../../../shared/animations';

/**
 * Trello "Gestión de roles y permisos" - listado (GET) + selector de rol editable (PUT).
 * El referente no puede cambiar su propio rol (lo valida el backend; acá lo deshabilitamos
 * directamente para no dejarlo intentar). "+ Nuevo usuario" abre el alta en un panel (wireframe),
 * en vez de navegar a una página aparte.
 */
@Component({
  selector: 'app-usuarios',
  imports: [AltaUsuarioComponent, BadgeComponent, ButtonComponent, CardComponent, EmptyStateComponent, InputComponent, ModalComponent, TableComponent, SelectComponent, InlineLoadingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal, fadeStagger],
  templateUrl: './usuarios.component.html',
  styleUrl: './usuarios.component.scss',
})
export class UsuariosComponent {
  private readonly service = inject(UserAdminService);
  private readonly session = inject(AuthSessionService);

  protected readonly roles: UserRole[] = ['ASEGURADO', 'ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA'];

  /** Misma lista, en el formato que espera app-select. */
  protected readonly roleOptions: SelectOption[] = this.roles.map((r) => ({
    value: r,
    label: this.roleLabel(r),
  }));

  protected readonly loading = signal(true);
  protected readonly hasError = signal(false);
  protected readonly users = signal<UserResponse[]>([]);
  protected readonly savingId = signal<number | null>(null);
  protected readonly roleError = signal<string | null>(null);
  protected readonly showCreatePanel = signal(false);

  protected readonly userToDelete = signal<UserResponse | null>(null);
  protected readonly deleting = signal(false);
  protected readonly deleteError = signal<string | null>(null);
  // Confirmación destructiva real ("doble verbo" del wireframe): el referente tiene que
  // tipear el nombre completo del usuario a eliminar. Un solo botón "Sí, eliminar" invitaba
  // a borrar de un click por accidente.
  protected readonly deleteConfirmText = signal('');
  protected readonly deleteConfirmTarget = computed(() => {
    const u = this.userToDelete();
    return u ? `${u.nombre} ${u.apellido}`.trim() : '';
  });
  protected readonly canConfirmDelete = computed(
    () =>
      this.userToDelete() !== null &&
      this.deleteConfirmText().trim().toLocaleLowerCase() ===
        this.deleteConfirmTarget().toLocaleLowerCase(),
  );

  protected readonly resendingId = signal<number | null>(null);
  protected readonly resendError = signal<string | null>(null);
  protected readonly resendOkId = signal<number | null>(null);

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

  protected statusLabel(estado: string): string {
    return userStatusLabel(estado);
  }

  protected statusTone(estado: string) {
    return userStatusTone(estado);
  }

  /** Only shown for PENDING rows in the template — the backend rejects it otherwise. */
  protected resendInvite(user: UserResponse): void {
    this.resendError.set(null);
    this.resendOkId.set(null);
    this.resendingId.set(user.id);

    this.service.resendInvite(user.id).subscribe({
      next: (updated) => {
        this.resendingId.set(null);
        this.resendOkId.set(updated.id);
        this.users.update((list) => list.map((u) => (u.id === updated.id ? updated : u)));
      },
      error: (err: HttpErrorResponse) => {
        this.resendingId.set(null);
        this.resendError.set(err.error?.detail ?? 'No se pudo reenviar la invitación. Probá de nuevo.');
      },
    });
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
    this.deleteConfirmText.set('');
    this.userToDelete.set(user);
  }

  protected cancelDelete(): void {
    this.userToDelete.set(null);
    this.deleteConfirmText.set('');
  }

  protected confirmDelete(): void {
    const user = this.userToDelete();
    if (!user || !this.canConfirmDelete()) {
      return;
    }
    this.deleting.set(true);
    this.service.delete(user.id).subscribe({
      next: () => {
        this.deleting.set(false);
        this.userToDelete.set(null);
        this.deleteConfirmText.set('');
        this.users.update((list) => list.filter((u) => u.id !== user.id));
      },
      error: (err: HttpErrorResponse) => {
        this.deleting.set(false);
        this.deleteError.set(err.error?.detail ?? 'No se pudo eliminar el usuario. Probá de nuevo.');
      },
    });
  }
}
