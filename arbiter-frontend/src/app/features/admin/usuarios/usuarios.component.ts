import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith } from 'rxjs';

import { UserAdminService, UserResponse } from '../../../core/auth/user-admin.service';
import { userRoleLabel } from '../../../core/models/user-role';

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: UserResponse[] }
  | { status: 'error' };

/** Trello "Gestión de roles y permisos" - solo el listado (GET). Sin selector de rol editable todavía. */
@Component({
  selector: 'app-usuarios',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './usuarios.component.html',
  styleUrl: './usuarios.component.scss',
})
export class UsuariosComponent {
  private readonly service = inject(UserAdminService);

  private readonly state = toSignal(
    this.service.list().pipe(
      map((data): LoadState => ({ status: 'ok', data })),
      startWith<LoadState>({ status: 'loading' }),
      catchError(() => of<LoadState>({ status: 'error' })),
    ),
    { initialValue: { status: 'loading' } as LoadState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly hasError = computed(() => this.state().status === 'error');

  protected readonly users = computed<UserResponse[]>(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : [];
  });

  protected readonly isEmpty = computed(() => this.state().status === 'ok' && this.users().length === 0);

  protected roleLabel(rol: string): string {
    return userRoleLabel(rol);
  }
}
