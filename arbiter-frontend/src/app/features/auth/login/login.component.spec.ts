import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { throwError } from 'rxjs';

import { LoginComponent } from './login.component';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { AuthService } from '../../../core/auth/auth.service';

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
