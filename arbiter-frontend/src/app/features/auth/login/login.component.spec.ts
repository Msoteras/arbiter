import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';

import { LoginComponent } from './login.component';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { AuthService, LoginResponse } from '../../../core/auth/auth.service';

interface LoginInternals {
  email: { set: (value: string) => void };
  password: { set: (value: string) => void };
  submit: () => void;
}

describe('LoginComponent · locked account', () => {
  async function errorShownFor(body: unknown): Promise<string> {
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            login: () => throwError(() => new HttpErrorResponse({ status: 423, error: body })),
          },
        },
        { provide: AuthSessionService, useValue: { start: () => undefined } },
      ],
    }).compileComponents();
    spyOn(console, 'error');

    const fixture = TestBed.createComponent(LoginComponent);
    const login = fixture.componentInstance as unknown as LoginInternals;
    login.email.set('analista@arbiter.test');
    login.password.set('wrong-password');
    login.submit();
    fixture.detectChanges();

    const error = (fixture.nativeElement as HTMLElement).querySelector('.error');
    return error?.textContent?.trim() ?? '';
  }

  it('shows the unlock time rounded up to the next minute', async () => {
    const lockedUntil = new Date(2026, 9, 23, 12, 19, 52).toISOString();

    const message = await errorShownFor({
      detail: 'Cuenta bloqueada temporalmente. Probá de nuevo más tarde.',
      lockedUntil,
    });

    expect(message).toBe('Cuenta bloqueada hasta las 12:20 por demasiados intentos fallidos.');
  });

  it('keeps an unlock time that already falls on the minute', async () => {
    const lockedUntil = new Date(2026, 9, 23, 12, 20, 0).toISOString();

    const message = await errorShownFor({ lockedUntil });

    expect(message).toBe('Cuenta bloqueada hasta las 12:20 por demasiados intentos fallidos.');
  });

  it('falls back to a generic message without an unlock time', async () => {
    const message = await errorShownFor(null);

    expect(message).toBe('Cuenta bloqueada temporalmente. Probá de nuevo más tarde.');
  });
});

describe('LoginComponent · return URL', () => {
  const insured: LoginResponse = {
    token: 'jwt',
    expiresAt: '2026-11-21T20:00:00Z',
    id: 7,
    email: 'martina@example.com',
    rol: 'ASEGURADO',
    nombre: 'Martina',
    apellido: 'López',
    insuredId: '42.987.654',
    onboardingComplete: true,
    insurerName: null,
    insurerLogoVersion: null,
  };

  /** Logs in from `url` and returns where the login navigated to. */
  async function landingAfterLoginFrom(url: string): Promise<string> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'login', component: LoginComponent }]),
        { provide: AuthService, useValue: { login: () => of(insured) } },
        { provide: AuthSessionService, useValue: { start: () => undefined } },
      ],
    });
    const harness = await RouterTestingHarness.create(url);
    const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);
    const login = harness.routeDebugElement!.componentInstance as LoginInternals;
    login.email.set(insured.email);
    login.password.set('secret');
    login.submit();
    return navigate.calls.mostRecent().args[0] as string;
  }

  it('brings the insured back to the case the mail linked', async () => {
    const landing = await landingAfterLoginFrom(
      '/login?returnUrl=%2Fportal%2Fcases%2F42%3Finsurer%3Dbbva',
    );

    expect(landing).toBe('/portal/cases/42?insurer=bbva');
  });

  it('goes home without a return URL', async () => {
    expect(await landingAfterLoginFrom('/login')).toBe('/portal/home');
  });

  it('ignores a return URL to another site', async () => {
    const landing = await landingAfterLoginFrom('/login?returnUrl=%2F%2Fevil.example%2Fphish');

    expect(landing).toBe('/portal/home');
  });
});
