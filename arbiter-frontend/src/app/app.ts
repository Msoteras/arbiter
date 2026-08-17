import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
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
import { LoadingComponent } from './shared/ui/loading/loading.component';
import { NotificationsPanelComponent } from './core/notifications/notifications-panel.component';
import { NuevaDenunciaComponent } from './features/expedientes/nueva-denuncia/nueva-denuncia.component';
import { GlobalSearchComponent } from './features/expedientes/global-search/global-search.component';

/** Debajo de este ancho el panel de navegación se superpone al contenido en vez de empujarlo. */
const OVERLAY_NAV_QUERY = '(max-width: 1024px)';
/** El panel recuerda si quedó abierto o cerrado entre recargas (solo en modo "empuja"). */
const NAV_OPEN_KEY = 'arbiter.nav-open';
/** Lo que dura el cierre de sesión en pantalla: alcanza para leerlo, no tanto como para estorbar. */
const LOGOUT_DELAY_MS = 900;

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
    GlobalSearchComponent,
    NotificationsPanelComponent,
    LoadingComponent,
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

  private readonly overlayNavMql =
    typeof window !== 'undefined' && window.matchMedia
      ? window.matchMedia(OVERLAY_NAV_QUERY)
      : null;
  /** true = el panel se superpone (pantalla angosta); false = empuja el contenido. */
  protected readonly overlayNav = signal(this.overlayNavMql?.matches ?? false);

  constructor() {
    this.reduceMotionMql?.addEventListener('change', (e) => this.reduceMotion.set(e.matches));
    this.overlayNavMql?.addEventListener('change', (e) => {
      this.overlayNav.set(e.matches);
      // Al angostarse la ventana el panel no puede quedar abierto tapando todo sin que nadie
      // lo haya pedido; al ensancharse vuelve a lo que el usuario había elegido.
      this.navOpen.set(e.matches ? false : this.storedNavOpen());
    });

    // Tied to the session and not to the constructor: the session lives in memory, so on reload
    // it's null for an instant and the count would come back empty.
    effect(() => {
      if (this.session.session()) {
        this.notifications.refreshUnreadCount();
      }
    });
  }

  // Solo NavigationEnd, no NavigationStart: el marco tiene que llegar DESPUÉS de que la pantalla
  // nueva esté montada, no antes. Adelantándolo al Start, al entrar recién logueado el chrome
  // aparecía mientras el outlet todavía mostraba el login — un frame de "app vacía" antes de la
  // carga de marca. Al revés no se ve nada: el home pinta su `app-loading` a viewport completo
  // apenas se monta, y el chrome se materializa detrás de ese overlay.
  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  // Las pantallas de autenticación son standalone (sin sesión todavía): pantalla completa,
  // sin el chrome de la app. Se comparan por path, ignorando el query (activación y reset
  // llevan el token en la URL).
  private static readonly AUTH_ROUTES = [
    '/login',
    '/forgot-password',
    '/activate-account',
    '/reset-password',
  ];
  // El shell exige sesión, no solo "no estar en una ruta de auth": la sesión vive en memoria y se
  // pierde al recargar, así que al refrescar una ruta protegida la URL sigue siendo /inbox por un
  // instante (antes de que el guard redirija a /login). Sin este chequeo, el chrome se pintaba
  // vacío ese instante — el "flash" del layout interno al recargar.
  protected readonly showShell = computed(
    () =>
      this.session.session() !== null && !App.AUTH_ROUTES.includes(this.currentUrl().split('?')[0]),
  );

  // H0003 - RBAC: cada rol ve solo su propia sección de la navegación (el referente incluida —
  // tiene acceso completo a nivel de permisos, pero en el nav solo se le muestra la suya).
  protected readonly showAnalistaNav = computed(
    () => this.session.session()?.rol === 'ANALISTA_SINIESTROS',
  );

  protected readonly showAseguradoNav = computed(() => this.session.session()?.rol === 'ASEGURADO');

  // H0002 - Alta de Usuarios: panel exclusivo del referente.
  protected readonly showAdminNav = computed(
    () => this.session.session()?.rol === 'REFERENTE_ASEGURADORA',
  );

  /** "Inicio" es lo único que la topbar navega; cada rol aterriza en su propio home. */
  protected readonly homeLink = computed(() => (this.showAdminNav() ? '/insurer/home' : '/home'));

  // Campana de la topbar interna (analista y referente). El asegurado también tiene campana, pero
  // en su propio topbar del portal (se renderiza directo ahí, sin pasar por este flag).
  protected readonly showBell = computed(() => {
    const rol = this.session.session()?.rol;
    return rol === 'ANALISTA_SINIESTROS' || rol === 'REFERENTE_ASEGURADORA';
  });

  // En el detalle de un expediente (/cases/:id) la topbar suma un "Volver a la bandeja" al lado de
  // "Inicio" — el detalle ya trae su propio encabezado con el N° de expediente.
  protected readonly showBack = computed(() =>
    this.currentUrl().split('?')[0].startsWith('/cases/'),
  );
  protected goBack(): void {
    this.router.navigateByUrl('/inbox');
  }

  // ───────────────── Panel de navegación desplegable ─────────────────
  // La topbar es fija y lleva solo "Inicio" + buscador + acciones; el resto de las secciones vive
  // en el panel que abre la hamburguesa. En pantalla ancha el panel empuja el contenido y su
  // estado se recuerda; en angosta se superpone y arranca siempre cerrado.
  protected readonly navOpen = signal(
    (this.overlayNavMql?.matches ?? false) ? false : this.storedNavOpen(),
  );

  private storedNavOpen(): boolean {
    if (typeof localStorage === 'undefined') return true;
    return localStorage.getItem(NAV_OPEN_KEY) !== 'false';
  }

  private readonly persistNavOpen = effect(() => {
    const open = this.navOpen();
    // Solo se recuerda la preferencia del modo "empuja": abrir el overlay en mobile es puntual,
    // no una preferencia de layout.
    if (!this.overlayNav() && typeof localStorage !== 'undefined') {
      localStorage.setItem(NAV_OPEN_KEY, String(open));
    }
  });

  // Superpuesto, el panel tapa el contenido: navegar tiene que cerrarlo. Cuando empuja, se queda
  // como estaba (es parte del layout, no un pop-up).
  private readonly closeNavOnNavigate = effect(() => {
    this.currentUrl();
    if (this.overlayNav()) {
      this.navOpen.set(false);
    }
  });

  protected toggleNav(): void {
    this.navOpen.update((open) => !open);
  }

  protected closeNav(): void {
    this.navOpen.set(false);
  }

  // ───────────────── Menú de perfil (topbar) ─────────────────
  // El perfil vive SOLO acá: el panel de navegación es desplegable y esconder ahí adentro el
  // "Cerrar sesión" lo dejaría a dos clicks. Antes estaba duplicado en las dos barras.
  protected readonly profileOpen = signal(false);

  protected toggleProfile(event: MouseEvent): void {
    // Sin esto el click llega a document y el listener de abajo lo cierra en el mismo tick.
    event.stopPropagation();
    // Los dos desplegables cuelgan de la misma esquina: abrir uno cierra al otro.
    this.showNotifications.set(false);
    this.profileOpen.update((open) => !open);
  }

  @HostListener('document:click')
  protected onDocumentClick(): void {
    this.profileOpen.set(false);
    this.showNotifications.set(false);
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    this.profileOpen.set(false);
    this.showNotifications.set(false);
    if (this.overlayNav() && this.navOpen()) this.navOpen.set(false);
  }

  // ───────────────── Notificaciones (desplegable anclado a la campana) ─────────────────
  // Era un modal: tapaba la pantalla entera para mostrar una lista corta que se lee de un vistazo.
  // Ahora se comporta como el menú de perfil — abre pegado a la campana y cierra al clickear
  // afuera, con Escape o al abrir el otro menú.
  protected readonly showNotifications = signal(false);

  protected toggleNotifications(event: MouseEvent): void {
    // Sin esto el click llega a document y el listener de abajo lo cierra en el mismo tick.
    event.stopPropagation();
    if (this.showNotifications()) {
      this.showNotifications.set(false);
      return;
    }
    this.profileOpen.set(false);
    this.showNotifications.set(true);
    // Abrir es "las vi": el servicio trae la lista y marca como leídas.
    this.notifications.openPanel();
  }

  protected closeNotifications(): void {
    this.showNotifications.set(false);
  }

  protected roleLabel(rol: string): string {
    return userRoleLabel(rol);
  }

  /** Iniciales para el avatar (ej. "María Gómez" → "MG"). */
  protected initials(nombre: string, apellido: string): string {
    return `${nombre?.[0] ?? ''}${apellido?.[0] ?? ''}`.toUpperCase() || '—';
  }

  // Cerrar sesión no es directo: pide confirmación (un click accidental en el ícono no debería
  // sacar al usuario de la app). Aplica a todos los roles (menú de perfil y topbar del portal).
  protected readonly showLogoutConfirm = signal(false);

  protected requestLogout(): void {
    this.profileOpen.set(false);
    this.showLogoutConfirm.set(true);
  }
  protected cancelLogout(): void {
    this.showLogoutConfirm.set(false);
  }
  /** Cerrar sesión muestra la carga de marca antes de soltar al login. */
  protected readonly loggingOut = signal(false);

  protected confirmLogout(): void {
    this.showLogoutConfirm.set(false);
    this.loggingOut.set(true);
    // La sesión se limpia recién al final, no acá: limpiarla ahora tira abajo el shell detrás del
    // overlay y la salida se ve como un parpadeo en vez de un cierre.
    setTimeout(() => {
      this.session.clear();
      this.notifications.clear();
      // El próximo ingreso vuelve a tener su carga de marca completa (login → home).
      this.appReady.reset();
      this.router.navigateByUrl('/login');
      this.loggingOut.set(false);
    }, LOGOUT_DELAY_MS);
  }
}
