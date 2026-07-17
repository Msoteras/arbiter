import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

import { AuthSessionService } from './core/auth/auth-session.service';
import { userRoleLabel } from './core/models/user-role';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly router = inject(Router);
  protected readonly session = inject(AuthSessionService);

  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  // La pantalla de login es standalone: sin topbar ni nav de la app.
  protected readonly showShell = computed(() => this.currentUrl() !== '/login');

  // H0003 - RBAC: el referente ve las dos secciones (acceso total); el resto, solo la propia.
  protected readonly showAnalistaNav = computed(() => {
    const rol = this.session.session()?.rol;
    return rol === 'ANALISTA_SINIESTROS' || rol === 'REFERENTE_ASEGURADORA';
  });

  protected readonly showAseguradoNav = computed(() => {
    const rol = this.session.session()?.rol;
    return rol === 'ASEGURADO' || rol === 'REFERENTE_ASEGURADORA';
  });

  protected roleLabel(rol: string): string {
    return userRoleLabel(rol);
  }

  protected logout(): void {
    this.session.clear();
    this.router.navigateByUrl('/login');
  }
}
