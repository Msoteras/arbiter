import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

import { AuthSessionService } from './core/auth/auth-session.service';
import { AppReadyService } from './core/app-ready.service';
import { NotificationsService } from './core/notifications/notifications.service';
import { NewClaimModalService } from './features/expedientes/new-claim-modal.service';
import { userRoleLabel } from './core/models/user-role';
import { LogoComponent } from './shared/ui/logo/logo.component';
import { ButtonComponent } from './shared/ui/button/button.component';
import { ModalComponent } from './shared/ui/modal/modal.component';
import { NuevaDenunciaComponent } from './features/expedientes/nueva-denuncia/nueva-denuncia.component';

@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    LogoComponent,
    ButtonComponent,
    ModalComponent,
    NuevaDenunciaComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Desactiva TODAS las animaciones de @angular/animations (stagger, growBar, etc.) del árbol
  // de la app cuando el sistema pide menos movimiento. Las animaciones CSS ya lo respetan por
  // su cuenta con media queries; esto cubre las de la DSL, que no lo hacen solas.
  host: { '[@.disabled]': 'reduceMotion()' },
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly router = inject(Router);
  protected readonly session = inject(AuthSessionService);
  protected readonly notifications = inject(NotificationsService);
  protected readonly newClaim = inject(NewClaimModalService);
  private readonly appReady = inject(AppReadyService);

  // Preferencia de movimiento reducido del sistema, reactiva a cambios en caliente.
  private readonly reduceMotionMql =
    typeof window !== 'undefined' && window.matchMedia
      ? window.matchMedia('(prefers-reduced-motion: reduce)')
      : null;
  protected readonly reduceMotion = signal(this.reduceMotionMql?.matches ?? false);

  constructor() {
    this.reduceMotionMql?.addEventListener('change', (e) => this.reduceMotion.set(e.matches));
  }

  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  // Las pantallas de autenticación son standalone (sin sesión todavía): pantalla completa,
  // sin sidebar ni nav de la app. Se comparan por path, ignorando el query (activación y reset
  // llevan el token en la URL).
  private static readonly AUTH_ROUTES = [
    '/login',
    '/forgot-password',
    '/activate-account',
    '/reset-password',
  ];
  // El shell exige sesión, no solo "no estar en una ruta de auth": la sesión vive en memoria y se
  // pierde al recargar, así que al refrescar una ruta protegida la URL sigue siendo /inbox por un
  // instante (antes de que el guard redirija a /login). Sin este chequeo, el sidebar se pintaba
  // vacío ese instante — el "flash" del layout interno al recargar.
  protected readonly showShell = computed(
    () =>
      this.session.session() !== null && !App.AUTH_ROUTES.includes(this.currentUrl().split('?')[0]),
  );

  // H0003 - RBAC: cada rol ve solo su propia sección del sidebar (el referente incluida —
  // tiene acceso completo a nivel de permisos, pero en el nav solo se le muestra la suya).
  protected readonly showAnalistaNav = computed(
    () => this.session.session()?.rol === 'ANALISTA_SINIESTROS',
  );

  protected readonly showAseguradoNav = computed(() => this.session.session()?.rol === 'ASEGURADO');

  // H0002 - Alta de Usuarios: panel exclusivo del referente.
  protected readonly showAdminNav = computed(
    () => this.session.session()?.rol === 'REFERENTE_ASEGURADORA',
  );

  // Campana de la topbar interna (analista y referente). El asegurado también tiene campana, pero
  // en su propio topbar del portal (se renderiza directo ahí, sin pasar por este flag).
  protected readonly showBell = computed(() => {
    const rol = this.session.session()?.rol;
    return rol === 'ANALISTA_SINIESTROS' || rol === 'REFERENTE_ASEGURADORA';
  });

  // Título de la topbar interna. Se muestra SOLO en el inicio ("Inicio"): el resto de las
  // pantallas internas ya traen su propio título en la página (ej. "Bandeja de expedientes"),
  // así que ahí la topbar va sin título para no duplicar. Vacío ('') = no se pinta título.
  protected readonly sectionTitle = computed(() => {
    const url = this.currentUrl().split('?')[0];
    return url === '/home' || url === '/insurer/home' ? 'Inicio' : '';
  });

  // La campana abre un panel de notificaciones. Hoy el productor real (polling/SSE) no está
  // cableado y el contador siempre es 0, así que el panel muestra el vacío honesto en vez de
  // un ícono decorativo que no hacía nada (ver bug #4 del relevamiento de UX).
  protected readonly showNotifications = signal(false);

  protected openNotifications(): void {
    this.showNotifications.set(true);
  }

  protected closeNotifications(): void {
    this.notifications.markAllRead();
    this.showNotifications.set(false);
  }

  protected roleLabel(rol: string): string {
    return userRoleLabel(rol);
  }

  /** Iniciales para el avatar de la sidebar (ej. "María Gómez" → "MG"). */
  protected initials(nombre: string, apellido: string): string {
    return `${nombre?.[0] ?? ''}${apellido?.[0] ?? ''}`.toUpperCase() || '—';
  }

  // Cerrar sesión no es directo: pide confirmación (un click accidental en el ícono no debería
  // sacar al usuario de la app). Aplica a todos los roles (sidebar interna y topbar del portal).
  protected readonly showLogoutConfirm = signal(false);

  protected requestLogout(): void {
    this.showLogoutConfirm.set(true);
  }
  protected cancelLogout(): void {
    this.showLogoutConfirm.set(false);
  }
  protected confirmLogout(): void {
    this.showLogoutConfirm.set(false);
    this.session.clear();
    // El próximo ingreso vuelve a tener su carga de marca completa (login → home).
    this.appReady.reset();
    this.router.navigateByUrl('/login');
  }
}
