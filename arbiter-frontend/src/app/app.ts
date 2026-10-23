import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, interval, map } from 'rxjs';

import { AuthSessionService } from './core/auth/auth-session.service';
import { AppReadyService } from './core/app-ready.service';
import { NotificationsService } from './core/notifications/notifications.service';
import { OnboardingService } from './core/onboarding/onboarding.service';
import { CaseMessagesService } from './features/cases/case-messages.service';
import { NewClaimModalService } from './features/cases/new-claim-modal.service';
import { homeRouteFor, userRoleLabel } from './core/models/user-role';
import { LogoComponent } from './shared/ui/logo/logo.component';
import { ButtonComponent } from './shared/ui/button/button.component';
import { ModalComponent } from './shared/ui/modal/modal.component';
import { LoadingComponent } from './shared/ui/loading/loading.component';
import { NotificationsPanelComponent } from './core/notifications/notifications-panel.component';
import { ToastStackComponent } from './shared/ui/toast/toast-stack.component';
import { ToastService } from './shared/ui/toast/toast.service';
import { NewClaimComponent } from './features/cases/new-claim/new-claim.component';
import { GlobalSearchComponent } from './features/cases/global-search/global-search.component';
import { ActivityBarComponent } from './shared/ui/activity-bar/activity-bar.component';
import { silentRequest } from './core/cache/query-cache';
import { InsurerLogoService } from './core/services/insurer-logo.service';

/** Below this width the nav panel overlays the content instead of pushing it. */
const OVERLAY_NAV_QUERY = '(max-width: 1024px)';
const NAV_OPEN_KEY = 'arbiter.nav-open';
const LOGOUT_DELAY_MS = 900;
const UNREAD_POLL_MS = 30_000;

@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    LogoComponent,
    ButtonComponent,
    ModalComponent,
    ToastStackComponent,
    NewClaimComponent,
    GlobalSearchComponent,
    NotificationsPanelComponent,
    LoadingComponent,
    ActivityBarComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // CSS animations honor prefers-reduced-motion via media queries; @angular/animations don't, so
  // they are disabled for the whole tree here.
  host: { '[@.disabled]': 'reduceMotion()' },
  templateUrl: './app.html',
  styleUrls: ['./app.scss', './app-portal.scss'],
})
export class App {
  private readonly router = inject(Router);
  protected readonly session = inject(AuthSessionService);
  protected readonly notifications = inject(NotificationsService);
  protected readonly messages = inject(CaseMessagesService);
  protected readonly newClaim = inject(NewClaimModalService);
  protected readonly onboarding = inject(OnboardingService);
  private readonly toast = inject(ToastService);
  private readonly appReady = inject(AppReadyService);
  protected readonly insurerLogo = inject(InsurerLogoService);

  private readonly reduceMotionMql =
    typeof window !== 'undefined' && window.matchMedia
      ? window.matchMedia('(prefers-reduced-motion: reduce)')
      : null;
  protected readonly reduceMotion = signal(this.reduceMotionMql?.matches ?? false);

  private readonly overlayNavMql =
    typeof window !== 'undefined' && window.matchMedia
      ? window.matchMedia(OVERLAY_NAV_QUERY)
      : null;
  protected readonly overlayNav = signal(this.overlayNavMql?.matches ?? false);

  constructor() {
    this.reduceMotionMql?.addEventListener('change', (e) => this.reduceMotion.set(e.matches));
    this.overlayNavMql?.addEventListener('change', (e) => {
      this.overlayNav.set(e.matches);
      // Narrowing must not leave an unrequested overlay covering the screen; widening restores
      // the user's stored preference.
      this.navOpen.set(e.matches ? false : this.storedNavOpen());
    });

    // Tied to the session and not to the constructor: the session lives in memory, so on reload
    // it's null for an instant and the count would come back empty.
    // The setup checklist is the referent's only: it feeds the home card, the menu dots and the
    // "step complete" toast.
    effect(() => {
      if (this.session.session()?.rol === 'REFERENTE_ASEGURADORA') {
        this.onboarding.load();
      } else {
        this.onboarding.clear();
      }
    });

    effect(() => {
      if (this.session.session()) {
        this.notifications.refreshUnreadCount(silentRequest());
      }
      if (this.canSeeMessages()) {
        this.messages.inbox(silentRequest()).subscribe({ error: () => undefined });
      }
    });

    // The effect above runs once per session; polling catches notices arriving with the screen
    // open. Skipped while the tab is hidden: nobody is watching the bell.
    interval(UNREAD_POLL_MS)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        if (document.visibilityState !== 'visible') {
          return;
        }
        if (this.session.session()) {
          this.notifications.refreshUnreadCount(silentRequest());
        }
        if (this.canSeeMessages()) {
          this.messages.inbox(silentRequest()).subscribe({ error: () => undefined });
        }
      });
  }

  private canSeeMessages(): boolean {
    return this.session.session() !== null;
  }

  // NavigationEnd, not NavigationStart: the chrome must appear after the new screen is mounted,
  // otherwise right after login it flashes over the still-visible login screen.
  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  // The full-viewport loader is for a session that starts on its home. Landing on any other screen
  // (a reload on Reglas, a link) means the app is already up: a later trip home must not cover the
  // whole window, nav included, with it.
  private readonly readyOffHome = effect(() => {
    const session = this.session.session();
    const path = this.currentUrl().split('?')[0];
    if (session && path !== homeRouteFor(session.rol) && !App.CHROMELESS_ROUTES.includes(path)) {
      untracked(() => this.appReady.markReady());
    }
  });

  // Matched by path without the query (activation and reset carry the token in the URL).
  // Onboarding has a session but no chrome: onboardingGuard bounces every portal link back to it.
  private static readonly CHROMELESS_ROUTES = [
    '/login',
    '/forgot-password',
    '/activate-account',
    '/reset-password',
    '/portal/onboarding',
  ];
  // The session lives in memory: on reload a protected URL stays up for an instant before the
  // guard redirects to /login, and without the session check an empty chrome would flash.
  protected readonly showShell = computed(
    () =>
      this.session.session() !== null &&
      !App.CHROMELESS_ROUTES.includes(this.currentUrl().split('?')[0]),
  );

  // Each role sees only its own nav section, even the referente, who has full permissions.
  protected readonly showAnalystNav = computed(
    () => this.session.session()?.rol === 'ANALISTA_SINIESTROS',
  );

  protected readonly showInsuredNav = computed(() => this.session.session()?.rol === 'ASEGURADO');

  protected readonly showAdminNav = computed(
    () => this.session.session()?.rol === 'REFERENTE_ASEGURADORA',
  );

  protected readonly homeLink = computed(() => (this.showAdminNav() ? '/insurer/home' : '/home'));

  // The insured's bell is rendered by the portal topbar, not through this flag.
  protected readonly showBell = computed(() => {
    const rol = this.session.session()?.rol;
    return rol === 'ANALISTA_SINIESTROS' || rol === 'REFERENTE_ASEGURADORA';
  });

  protected readonly showBack = computed(() =>
    this.currentUrl().split('?')[0].startsWith('/cases/'),
  );
  protected goBack(): void {
    this.router.navigateByUrl('/inbox');
  }

  // Nav panel: on wide screens it pushes the content and its state is remembered; on narrow ones
  // it overlays and always starts closed.
  protected readonly navOpen = signal(
    (this.overlayNavMql?.matches ?? false) ? false : this.storedNavOpen(),
  );

  private storedNavOpen(): boolean {
    if (typeof localStorage === 'undefined') return true;
    return localStorage.getItem(NAV_OPEN_KEY) !== 'false';
  }

  private readonly persistNavOpen = effect(() => {
    const open = this.navOpen();
    // Opening the mobile overlay is a one-off action, not a layout preference.
    if (!this.overlayNav() && typeof localStorage !== 'undefined') {
      localStorage.setItem(NAV_OPEN_KEY, String(open));
    }
  });

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

  protected readonly profileOpen = signal(false);

  protected toggleProfile(event: MouseEvent): void {
    // Otherwise the click reaches document and the listener below closes it in the same tick.
    event.stopPropagation();
    this.showNotifications.set(false);
    this.portalNavOpen.set(false);
    this.profileOpen.update((open) => !open);
  }

  protected readonly portalNavOpen = signal(false);

  protected togglePortalNav(event: MouseEvent): void {
    event.stopPropagation();
    this.profileOpen.set(false);
    this.showNotifications.set(false);
    this.portalNavOpen.update((open) => !open);
  }

  @HostListener('document:click')
  protected onDocumentClick(): void {
    this.profileOpen.set(false);
    this.portalNavOpen.set(false);
    this.showNotifications.set(false);
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    this.profileOpen.set(false);
    this.portalNavOpen.set(false);
    this.showNotifications.set(false);
    if (this.overlayNav() && this.navOpen()) this.navOpen.set(false);
  }

  protected readonly showNotifications = signal(false);

  protected toggleNotifications(event: MouseEvent): void {
    // Otherwise the click reaches document and the listener below closes it in the same tick.
    event.stopPropagation();
    if (this.showNotifications()) {
      this.showNotifications.set(false);
      return;
    }
    this.profileOpen.set(false);
    this.portalNavOpen.set(false);
    this.showNotifications.set(true);
    // Opening the panel marks the notifications as read.
    this.notifications.openPanel();
  }

  protected closeNotifications(): void {
    this.showNotifications.set(false);
  }

  protected roleLabel(rol: string): string {
    return userRoleLabel(rol);
  }

  protected initials(firstName: string, lastName: string): string {
    return `${firstName?.[0] ?? ''}${lastName?.[0] ?? ''}`.toUpperCase() || '—';
  }

  /** Initials of the insurer's first two words: "Seguros del Sur" → "SD". */
  protected monogram(insurerName: string): string {
    const [first = '', second = ''] = insurerName.trim().split(/\s+/);
    return `${first[0] ?? ''}${second[0] ?? ''}`.toUpperCase();
  }

  /** Testing aid: walks the setup checklist again without touching the configuration. */
  protected readonly showOnboardingReset = signal(false);
  protected readonly resettingOnboarding = signal(false);

  protected requestOnboardingReset(): void {
    this.profileOpen.set(false);
    this.showOnboardingReset.set(true);
  }

  protected cancelOnboardingReset(): void {
    this.showOnboardingReset.set(false);
  }

  protected confirmOnboardingReset(): void {
    this.resettingOnboarding.set(true);
    this.onboarding.reset().subscribe({
      next: () => {
        this.resettingOnboarding.set(false);
        this.showOnboardingReset.set(false);
      },
      error: () => {
        this.resettingOnboarding.set(false);
        this.toast.show('No se pudo reiniciar la puesta en marcha.', 'danger');
      },
    });
  }

  protected readonly showLogoutConfirm = signal(false);

  protected requestLogout(): void {
    this.profileOpen.set(false);
    this.showLogoutConfirm.set(true);
  }
  protected cancelLogout(): void {
    this.showLogoutConfirm.set(false);
  }
  protected readonly loggingOut = signal(false);

  protected confirmLogout(): void {
    this.showLogoutConfirm.set(false);
    this.loggingOut.set(true);
    // Clearing the session now would tear down the shell behind the overlay and flicker.
    setTimeout(() => {
      this.session.clear();
      this.notifications.clear();
      this.appReady.reset();
      this.router.navigateByUrl('/login');
      this.loggingOut.set(false);
    }, LOGOUT_DELAY_MS);
  }
}
