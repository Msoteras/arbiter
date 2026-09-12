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
 * El recorte "en curso" de la bandeja. Lo que se verifica es qué se le pide al backend: filtrar la
 * página ya traída dejaría páginas de tamaño variable y un total que miente, así que el recorte
 * tiene que viajar en los params — del listado y de los conteos, o el toggle contradice a la tabla.
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
              return of({ mine: 0, all: 0, assigned: 0, unassigned: 0, fraud: 0 });
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

  /** Los miembros del componente son `protected`: la vista los usa, el spec los alcanza así. */
  function signalOf(name: string): { set: (value: unknown) => void } {
    return (
      fixture.componentInstance as unknown as Record<string, { set: (value: unknown) => void }>
    )[name];
  }

  function call(name: string): void {
    (fixture.componentInstance as unknown as Record<string, () => void>)[name]();
  }

  /** El recorte ya no es un control aparte: es una pestaña más de la barra de arriba. */
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

  /** El referente también: el recorte es sobre qué hay para trabajar, no sobre de quién es. */
  it('arranca igual para el referente', async () => {
    await mount('REFERENTE_ASEGURADORA');

    expect(listCalls[0].scope).toBe('OPEN');
  });

  /**
   * Los conteos se piden SIN recorte por ciclo: cada pestaña muestra cuántos expedientes va a
   * encontrar quien entre en ella. Si heredaran el recorte de la pestaña activa, parado en
   * "Cerrados" el contador de "Sin asignar" contaría solo cerrados y cambiaría al entrar.
   */
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

  /** La página 4 de un recorte puede no existir en el otro. */
  it('cambiar el recorte vuelve a la primera página', async () => {
    await mount();
    signalOf('page').set(3);
    fixture.detectChanges();

    clickScope('Cerrados');
    await fixture.whenStable();

    expect(lastList().page).toBe(0);
  });

  /**
   * El caso que más confunde al analista: "Aprobado" con el recorte en "En curso" no tiene
   * resultados posibles. En vez de devolver una lista vacía inexplicable, el recorte se afloja
   * solo — y se ve, porque el control se mueve.
   */
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
