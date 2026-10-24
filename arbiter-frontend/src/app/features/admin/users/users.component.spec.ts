import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { UsersComponent } from './users.component';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { UserAdminService, UserResponse } from '../../../core/auth/user-admin.service';

describe('UsersComponent', () => {
  let fixture: ComponentFixture<UsersComponent>;

  const user = (over: Partial<UserResponse>): UserResponse => ({
    id: 1,
    email: 'x@arbiter.com',
    nombre: 'Ana',
    apellido: 'Paz',
    rol: 'ANALISTA_SINIESTROS',
    estado: 'ACTIVE',
    createdAt: '2026-10-01T10:00:00Z',
    lastAccessAt: null,
    ...over,
  });

  const USERS = [
    user({
      id: 1,
      email: 'ref@arbiter.com',
      nombre: 'Sofía',
      apellido: 'Martínez',
      rol: 'REFERENTE_ASEGURADORA',
    }),
    user({ id: 2, email: 'lucas@arbiter.com', nombre: 'Lucas', apellido: 'Gómez' }),
    user({
      id: 3,
      email: 'paula@arbiter.com',
      nombre: 'Paula',
      apellido: 'Ríos',
      estado: 'PENDING',
    }),
    user({ id: 4, email: 'juan@mail.com', nombre: 'Juan', apellido: 'Pérez', rol: 'ASEGURADO' }),
  ];

  async function mount(): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [UsersComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: UserAdminService, useValue: { list: () => of(USERS) } },
        {
          provide: AuthSessionService,
          useValue: { session: signal({ email: 'ref@arbiter.com' }) },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(UsersComponent);
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function rows(): HTMLElement[] {
    return Array.from(el().querySelectorAll<HTMLElement>('tbody tr'));
  }

  function tab(label: string): HTMLButtonElement {
    return Array.from(el().querySelectorAll<HTMLButtonElement>('[role="tab"]')).find((t) =>
      t.textContent!.includes(label),
    )!;
  }

  it('counts each tab and narrows the list to the chosen one', async () => {
    await mount();

    expect(rows().length).toBe(4);
    expect(tab('Analistas').textContent).toContain('2');
    expect(tab('Invitaciones').textContent).toContain('1');

    tab('Invitaciones').click();
    fixture.detectChanges();

    expect(rows().length).toBe(1);
    expect(rows()[0].textContent).toContain('Paula Ríos');
    expect(rows()[0].textContent).toContain('Nunca entró');
  });

  it('searches by name or email', async () => {
    await mount();
    const input = el().querySelector<HTMLInputElement>('.search input')!;

    input.value = 'lucas@';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(rows().length).toBe(1);
    expect(rows()[0].textContent).toContain('Lucas Gómez');
  });

  it('marks the own row and gives it no actions', async () => {
    await mount();
    const own = rows()[0];

    expect(own.textContent).toContain('Vos');
    expect(own.querySelector('app-menu-button')).toBeNull();
    expect(rows()[1].querySelector('app-menu-button')).not.toBeNull();
  });
});
