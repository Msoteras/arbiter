import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

import { AuthSessionService } from './core/auth/auth-session.service';
import { NotificationsService } from './core/notifications/notifications.service';
import { userRoleLabel } from './core/models/user-role';
import { LogoComponent } from './shared/ui/logo/logo.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, LogoComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly router = inject(Router);
  protected readonly session = inject(AuthSessionService);
  protected readonly notifications = inject(NotificationsService);

  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  // La pantalla de login es standalone: sin sidebar ni nav de la app.
  protected readonly showShell = computed(() => this.currentUrl() !== '/login');

  // H0003 - RBAC: cada rol ve solo su propia sección del sidebar (el referente incluida —
  // tiene acceso completo a nivel de permisos, pero en el nav solo se le muestra la suya).
  protected readonly showAnalistaNav = computed(() => this.session.session()?.rol === 'ANALISTA_SINIESTROS');

  protected readonly showAseguradoNav = computed(() => this.session.session()?.rol === 'ASEGURADO');

  // H0002 - Alta de Usuarios: panel exclusivo del referente.
  protected readonly showAdminNav = computed(
    () => this.session.session()?.rol === 'REFERENTE_ASEGURADORA',
  );

  // Campana de notificaciones: solo roles internos (analista y referente), como en el
  // wireframe. El asegurado no la tiene.
  protected readonly showBell = computed(() => {
    const rol = this.session.session()?.rol;
    return rol === 'ANALISTA_SINIESTROS' || rol === 'REFERENTE_ASEGURADORA';
  });

  protected roleLabel(rol: string): string {
    return userRoleLabel(rol);
  }

  /** Iniciales para el avatar de la sidebar (ej. "María Gómez" → "MG"). */
  protected initials(nombre: string, apellido: string): string {
    return `${nombre?.[0] ?? ''}${apellido?.[0] ?? ''}`.toUpperCase() || '—';
  }

  protected logout(): void {
    this.session.clear();
    this.router.navigateByUrl('/login');
  }
}
