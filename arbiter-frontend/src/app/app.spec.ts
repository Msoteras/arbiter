import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { App } from './app';
import { AuthSessionService } from './core/auth/auth-session.service';
import { UserRole } from './core/models/user-role';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        // The root host binds [@.disabled] to honour prefers-reduced-motion.
        provideNoopAnimations(),
      ],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);

    expect(fixture.componentInstance).toBeTruthy();
  });

  /** Without a session there is no role to build a menu for, so neither header may render. */
  it('should not render any navigation chrome when nobody is logged in', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.topbar')).toBeNull();
    expect(compiled.querySelector('.appbar')).toBeNull();
  });

  describe('side menu', () => {
    afterEach(() => TestBed.inject(AuthSessionService).clear());

    function menuFor(rol: UserRole): (string | undefined)[] {
      TestBed.inject(AuthSessionService).start({
        token: 'test-token',
        expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
        id: 1,
        email: 'usuario@arbiter.test',
        rol,
        nombre: 'Lucas',
        apellido: 'Gómez',
        insuredId: null,
        onboardingComplete: null,
        insurerName: 'BBVA',
      });
      const fixture = TestBed.createComponent(App);
      fixture.detectChanges();
      return [...(fixture.nativeElement as HTMLElement).querySelectorAll('.navpanel-nav a')].map(
        (link) => link.textContent?.trim(),
      );
    }

    it('gives the analyst a Reglas entry and no referente management', () => {
      const links = menuFor('ANALISTA_SINIESTROS');

      expect(links).toEqual(['Bandeja', 'Mensajes', 'Dashboard', 'Reportes', 'Arbiter', 'Reglas']);
    });

    it('keeps Reglas among the referente management entries', () => {
      const links = menuFor('REFERENTE_ASEGURADORA');

      expect(links).toContain('Usuarios');
      expect(links).toContain('Reglas');
      expect(links).toContain('Autorizaciones');
    });
  });
});
