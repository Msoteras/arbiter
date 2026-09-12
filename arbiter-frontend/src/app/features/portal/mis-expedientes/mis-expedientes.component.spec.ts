import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { MisExpedientesComponent } from './mis-expedientes.component';
import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { Policy } from '../../../core/models/policy';
import { ExpedienteListParams, ExpedienteService } from '../../expedientes/expediente.service';
import { NewClaimModalService } from '../../expedientes/new-claim-modal.service';
import { PolicyService } from '../../expedientes/policy.service';

/**
 * El filtro por estado del portal: el asegurado elige entre tres cajones y el backend filtra por
 * `CaseStatus`. Lo que se verifica es la traducción — "En trámite" son cuatro estados, y si se
 * manda uno solo la lista miente por omisión.
 */
describe('MisExpedientesComponent · filtros', () => {
  let fixture: ComponentFixture<MisExpedientesComponent>;
  let listCalls: ExpedienteListParams[];

  const emptyPage = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 };

  const policy = (insurerId: string, insurerName: string): Policy =>
    ({ insurerId, insurerName }) as Policy;

  async function mount(policies: Policy[]): Promise<void> {
    listCalls = [];

    await TestBed.configureTestingModule({
      imports: [MisExpedientesComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        {
          provide: ExpedienteService,
          useValue: {
            list: (params: ExpedienteListParams) => {
              listCalls.push(params);
              return of(emptyPage);
            },
          },
        },
        { provide: PolicyService, useValue: { listByInsured: () => of(policies) } },
        { provide: NewClaimModalService, useValue: { open: () => undefined } },
        { provide: InsuredSessionService, useValue: { insuredId: signal('42.987.654') } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MisExpedientesComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function signalOf(name: string): { set: (value: unknown) => void } {
    return (
      fixture.componentInstance as unknown as Record<string, { set: (value: unknown) => void }>
    )[name];
  }

  function lastList(): ExpedienteListParams {
    return listCalls[listCalls.length - 1];
  }

  const bbva = policy('1', 'BBVA Seguros');
  const provincia = policy('2', 'Provincia Seguros');

  it('sin filtros no manda estado ni aseguradora', async () => {
    await mount([bbva]);

    expect(lastList().status).toBeUndefined();
    expect(lastList().insurerId).toBeUndefined();
    expect(lastList().insuredId).toBe('42.987.654');
  });

  it('"En trámite" se expande a los cuatro estados del cajón', async () => {
    await mount([bbva]);

    signalOf('estadoFilter').set('EN_TRAMITE');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(lastList().status).toEqual([
      'PENDING_ANALYST_REVIEW',
      'CLASSIFICATION_FAILED',
      'AWAITING_DOCUMENTATION',
      'PENDING_EXPERT_REPORT',
      'PENDING_REPAIR',
    ]);
  });

  it('"Terminado" manda los tres estados finales', async () => {
    await mount([bbva]);

    signalOf('estadoFilter').set('TERMINADO');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(lastList().status).toEqual(['APPROVED', 'REJECTED', 'LAPSED']);
  });

  /** Con una sola compañía el filtro sobra: todos los siniestros son de ella. */
  it('con una sola aseguradora no ofrece el filtro', async () => {
    await mount([bbva]);

    expect(fixture.nativeElement.textContent).not.toContain('Aseguradora');
  });

  it('con pólizas en dos compañías sí lo ofrece', async () => {
    await mount([bbva, provincia]);

    expect(fixture.nativeElement.textContent).toContain('Aseguradora');
  });

  /** Dos pólizas de la misma compañía son una sola opción. */
  it('no repite la aseguradora cuando hay varias pólizas de la misma', async () => {
    await mount([bbva, policy('1', 'BBVA Seguros')]);

    expect(fixture.nativeElement.textContent).not.toContain('Aseguradora');
  });

  /** La página 3 de un filtro puede no existir en el siguiente. */
  it('cambiar un filtro vuelve a la primera página', async () => {
    await mount([bbva]);
    signalOf('page').set(2);
    fixture.detectChanges();
    await fixture.whenStable();

    signalOf('estadoFilter').set('TERMINADO');
    (fixture.componentInstance as unknown as Record<string, () => void>)['onFilterChange']();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(lastList().page).toBe(0);
  });

  /** El rango filtra por fecha del hecho, no por la de denuncia. */
  it('las fechas viajan como rango del hecho', async () => {
    await mount([bbva]);

    signalOf('desde').set('2026-08-01');
    signalOf('hasta').set('2026-08-31');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(lastList().eventDateFrom).toBe('2026-08-01');
    expect(lastList().eventDateTo).toBe('2026-08-31');
  });
});
