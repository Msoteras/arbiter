import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { UserAdminService, UserResponse } from '../../../core/auth/user-admin.service';
import { UserRole, userRoleLabel } from '../../../core/models/user-role';
import { userStatusLabel, userStatusTone } from '../../../core/models/user-status';
import { formatDateTime } from '../../../core/util/datetime';
import { CreateUserComponent } from '../create-user/create-user.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ChipGroupComponent, ChipOption } from '../../../shared/ui/chip-group/chip-group.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import {
  MenuButtonComponent,
  MenuItem,
} from '../../../shared/ui/menu-button/menu-button.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { TableComponent } from '../../../shared/ui/table/table.component';
import { TabOption, TabsComponent } from '../../../shared/ui/tabs/tabs.component';
import { SkeletonComponent } from '../../../shared/ui/skeleton/skeleton.component';
import { ToastService } from '../../../shared/ui/toast/toast.service';
import { fadeStagger, staggerReveal } from '../../../shared/animations';
import { OnboardingTargetDirective } from '../../../core/onboarding/onboarding-target.directive';

type UserLens = 'ALL' | 'ANALYSTS' | 'REFERENTS' | 'INSURED' | 'INVITES';

const LENSES: { id: UserLens; label: string }[] = [
  { id: 'ALL', label: 'Todos' },
  { id: 'ANALYSTS', label: 'Analistas' },
  { id: 'REFERENTS', label: 'Referentes' },
  { id: 'INSURED', label: 'Asegurados' },
  { id: 'INVITES', label: 'Invitaciones' },
];

function matchesLens(user: UserResponse, lens: UserLens): boolean {
  switch (lens) {
    case 'ANALYSTS':
      return user.rol === 'ANALISTA_SINIESTROS';
    case 'REFERENTS':
      return user.rol === 'REFERENTE_ASEGURADORA';
    case 'INSURED':
      return user.rol === 'ASEGURADO';
    case 'INVITES':
      return user.estado === 'PENDING';
    default:
      return true;
  }
}

/** The referente can't change their own role: the backend enforces it, the UI just disables it. */
@Component({
  selector: 'app-users',
  imports: [
    OnboardingTargetDirective,
    CreateUserComponent,
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    ChipGroupComponent,
    EmptyStateComponent,
    InputComponent,
    MenuButtonComponent,
    ModalComponent,
    TableComponent,
    TabsComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal, fadeStagger],
  templateUrl: './users.component.html',
  styleUrl: './users.component.scss',
})
export class UsersComponent {
  private readonly service = inject(UserAdminService);
  private readonly session = inject(AuthSessionService);
  private readonly toastService = inject(ToastService);

  // Insureds come from the insurer's directory: their role is never editable.
  protected readonly roles: UserRole[] = ['ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA'];

  protected readonly roleOptions: ChipOption[] = this.roles.map((r) => ({
    value: r,
    label: this.roleLabel(r),
  }));

  protected readonly loading = signal(true);
  protected readonly hasError = signal(false);
  protected readonly users = signal<UserResponse[]>([]);

  protected readonly userToEditRole = signal<UserResponse | null>(null);
  protected readonly roleDraft = signal<string>('');
  protected readonly savingRole = signal(false);
  protected readonly roleError = signal<string | null>(null);
  protected readonly showCreatePanel = signal(false);

  protected readonly userToDelete = signal<UserResponse | null>(null);
  protected readonly deleting = signal(false);
  protected readonly deleteError = signal<string | null>(null);
  // Deleting requires typing the user's full name, to prevent one-click accidental deletes.
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

  protected readonly query = signal('');
  protected readonly lens = signal<UserLens>('ALL');

  private readonly searchedUsers = computed(() => {
    const q = this.query().trim().toLocaleLowerCase();
    if (!q) return this.users();
    return this.users().filter((u) =>
      `${u.nombre} ${u.apellido} ${u.email}`.toLocaleLowerCase().includes(q),
    );
  });

  /** Counts follow the search, so each tab says how many rows it would show. */
  protected readonly lensTabs = computed<TabOption[]>(() => {
    const list = this.searchedUsers();
    return LENSES.map((l) => ({
      id: l.id,
      label: l.label,
      count: list.filter((u) => matchesLens(u, l.id)).length,
    }));
  });

  protected readonly visibleUsers = computed(() =>
    this.searchedUsers().filter((u) => matchesLens(u, this.lens())),
  );

  /** Built once per list change, so each row's menu keeps the same items between checks. */
  protected readonly rowActions = computed(
    () => new Map(this.users().map((u) => [u.id, this.actionsFor(u)])),
  );

  // Bulk insured provisioning asks for confirmation: it sends real emails and can't be undone.
  protected readonly showProvisionConfirm = signal(false);
  protected readonly provisioning = signal(false);
  protected readonly provisionStarted = signal(false);

  protected readonly isEmpty = computed(
    () => !this.loading() && !this.hasError() && this.users().length === 0,
  );

  constructor() {
    this.load();
  }

  /** The backend answers 202 and keeps working, so there's no summary: only "started". */
  protected lastAccess(user: UserResponse): string {
    if (user.estado === 'PENDING') return 'Nunca entró';
    return formatDateTime(user.lastAccessAt, 'Sin registro');
  }

  protected initials(user: UserResponse): string {
    return `${user.nombre.charAt(0)}${user.apellido.charAt(0)}`.toLocaleUpperCase();
  }

  private actionsFor(user: UserResponse): MenuItem[] {
    const items: MenuItem[] = [];
    if (this.canEditRole(user)) items.push({ value: 'role', label: 'Cambiar rol' });
    if (user.estado === 'PENDING') items.push({ value: 'resend', label: 'Reenviar invitación' });
    if (!this.isSelf(user)) items.push({ value: 'delete', label: 'Eliminar', danger: true });
    return items;
  }

  protected onAction(user: UserResponse, action: string): void {
    if (action === 'role') this.requestRoleChange(user);
    else if (action === 'resend') this.resendInvite(user);
    else if (action === 'delete') this.requestDelete(user);
  }

  protected confirmProvision(): void {
    this.provisioning.set(true);
    this.service.provisionInsured().subscribe({
      next: () => {
        this.provisioning.set(false);
        this.showProvisionConfirm.set(false);
        this.provisionStarted.set(true);
      },
      error: (err: HttpErrorResponse) => {
        this.provisioning.set(false);
        this.showProvisionConfirm.set(false);
        this.toastService.show(
          err.error?.detail ?? 'No se pudo iniciar el alta masiva. Probá de nuevo.',
        );
      },
    });
  }

  protected refresh(): void {
    this.provisionStarted.set(false);
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

  protected canEditRole(user: UserResponse): boolean {
    return !this.isSelf(user) && user.rol !== 'ASEGURADO';
  }

  protected onCreated(user: UserResponse): void {
    this.users.update((list) => [user, ...list]);
    this.showCreatePanel.set(false);
  }

  protected roleLabel(rol: string): string {
    return userRoleLabel(rol);
  }

  protected statusLabel(status: string): string {
    return userStatusLabel(status);
  }

  protected statusTone(status: string) {
    return userStatusTone(status);
  }

  /** Only shown for PENDING rows in the template — the backend rejects it otherwise. */
  protected resendInvite(user: UserResponse): void {
    this.resendingId.set(user.id);

    this.service.resendInvite(user.id).subscribe({
      next: (updated) => {
        this.resendingId.set(null);
        this.users.update((list) => list.map((u) => (u.id === updated.id ? updated : u)));
        this.toastService.show(`Invitación reenviada a ${updated.email}.`, 'ok');
      },
      error: (err: HttpErrorResponse) => {
        this.resendingId.set(null);
        this.toastService.show(
          err.error?.detail ?? 'No se pudo reenviar la invitación. Probá de nuevo.',
        );
      },
    });
  }

  protected requestRoleChange(user: UserResponse): void {
    this.roleError.set(null);
    this.roleDraft.set(user.rol);
    this.userToEditRole.set(user);
  }

  protected cancelRoleChange(): void {
    this.userToEditRole.set(null);
  }

  protected confirmRoleChange(): void {
    const user = this.userToEditRole();
    const newRole = this.roleDraft() as UserRole;
    if (!user || newRole === user.rol) {
      return;
    }
    this.roleError.set(null);
    this.savingRole.set(true);

    this.service.updateRole(user.id, newRole).subscribe({
      next: (updated) => {
        this.savingRole.set(false);
        this.userToEditRole.set(null);
        this.users.update((list) => list.map((u) => (u.id === updated.id ? updated : u)));
      },
      error: (err: HttpErrorResponse) => {
        this.savingRole.set(false);
        this.roleError.set(err.error?.detail ?? 'No se pudo cambiar el rol. Probá de nuevo.');
      },
    });
  }

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
        this.deleteError.set(
          err.error?.detail ?? 'No se pudo eliminar el usuario. Probá de nuevo.',
        );
      },
    });
  }
}
