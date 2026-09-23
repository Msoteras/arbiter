import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';

import { BandejaComponent } from './bandeja.component';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { UserAdminService } from '../../../core/auth/user-admin.service';
import { CaseNavigationService } from '../case-navigation.service';
import { ExpedienteListParams, ExpedienteService } from '../expediente.service';

/**
 * The lifecycle scope must travel in the request params: filtering the fetched page client-side
 * would yield uneven pages and a wrong total.
 */
describe('BandejaComponent · recorte en curso', () => {
  let fixture: ComponentFixture<BandejaComponent>;
  let listCalls: ExpedienteListParams[];
  let lensCalls: ExpedienteListParams[];

  const emptyPage = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 };

  async function mount(rol = 'ANALISTA_SINIESTROS'): Promise<void> {
    listCalls = [];
    lensCalls = [];

    await TestBed.configureTestingModule({
      imports: [BandejaComponent],
      providers: [
        provideNoopAnimations(),
        {
          provide: ExpedienteService,
          useValue: {
            list: (params: ExpedienteListParams) => {
              listCalls.push(params);
              return of(emptyPage);
            },
            lensSummary: (params: ExpedienteListParams) => {
              lensCalls.push(params);
              return of({
                mine: 0,
                all: 0,
                assigned: 0,
                unassigned: 0,
                fraud: 0,
                open: 0,
                closed: 0,
              });
            },
            claimCauseNames: () => of([]),
            analystWorkload: () => of([]),
          },
        },
        { provide: UserAdminService, useValue: { listAnalysts: () => of([]) } },
        { provide: CaseNavigationService, useValue: { setSequence: () => undefined } },
        {
          provide: AuthSessionService,
          useValue: { session: () => ({ rol, email: 'lucas@bbva.com' }) },
        },
        { provide: Router, useValue: { navigate: () => Promise.resolve(true) } },
        { provide: ActivatedRoute, useValue: { queryParamMap: of(new Map()) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(BandejaComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** Component members are `protected`; this is how the spec reaches them. */
  function signalOf(name: string): { set: (value: unknown) => void } {
    return (
      fixture.componentInstance as unknown as Record<string, { set: (value: unknown) => void }>
    )[name];
  }

  function call(name: string): void {
    (fixture.componentInstance as unknown as Record<string, () => void>)[name]();
  }

  function clickScope(label: string): void {
    const button = (
      Array.from(fixture.nativeElement.querySelectorAll('.lens-tab')) as HTMLButtonElement[]
    ).find((b) => b.textContent?.trim().startsWith(label));
    button!.click();
    fixture.detectChanges();
  }

  function lastList(): ExpedienteListParams {
    return listCalls[listCalls.length - 1];
  }

  it('arranca pidiendo solo los expedientes en curso', async () => {
    await mount();

    expect(listCalls[0].scope).toBe('OPEN');
  });

  it('arranca igual para el referente', async () => {
    await mount('REFERENTE_ASEGURADORA');

    expect(listCalls[0].scope).toBe('OPEN');
  });

  /** Each tab's count must be what that tab will show, independent of the active scope. */
  it('los conteos de las pestañas no arrastran el recorte', async () => {
    await mount();

    expect(lensCalls[0].scope).toBeUndefined();
  });

  it('cambiar el recorte se lo pide al backend', async () => {
    await mount();

    clickScope('Cerrados');
    await fixture.whenStable();
    expect(lastList().scope).toBe('CLOSED');

    clickScope('Todos');
    await fixture.whenStable();
    expect(lastList().scope).toBe('ALL');
  });

  it('cambiar el recorte vuelve a la primera página', async () => {
    await mount();
    signalOf('page').set(3);
    fixture.detectChanges();

    clickScope('Cerrados');
    await fixture.whenStable();

    expect(lastList().page).toBe(0);
  });

  /** A closed status under the "open" scope can never match, so the scope widens itself. */
  it('elegir un estado cerrado afloja el recorte a todos', async () => {
    await mount();
    expect(listCalls[0].scope).toBe('OPEN');

    signalOf('draftStatus').set('APPROVED');
    call('applyFilters');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(lastList().scope).toBe('ALL');
    expect(lastList().status).toBe('APPROVED');
    const active = fixture.nativeElement.querySelector('.lens-tab.active') as HTMLElement;
    expect(active.textContent?.trim()).toContain('Todos');
  });
});
