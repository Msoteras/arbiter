import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { MyCasesComponent } from './my-cases.component';
import { InsuredSessionService } from '../../../core/auth/insured-session.service';
import { Policy } from '../../../core/models/policy';
import { CaseListParams, CaseService } from '../../cases/case.service';
import { NewClaimModalService } from '../../cases/new-claim-modal.service';
import { PolicyService } from '../../cases/policy.service';

/** Each simplified status bucket must expand to every `CaseStatus` it covers. */
describe('MyCasesComponent · filters', () => {
  let fixture: ComponentFixture<MyCasesComponent>;
  let listCalls: CaseListParams[];

  const emptyPage = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 };

  const policy = (insurerId: string, insurerName: string): Policy =>
    ({ insurerId, insurerName }) as Policy;

  async function mount(policies: Policy[]): Promise<void> {
    listCalls = [];

    await TestBed.configureTestingModule({
      imports: [MyCasesComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        {
          provide: CaseService,
          useValue: {
            list: (params: CaseListParams) => {
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

    fixture = TestBed.createComponent(MyCasesComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function signalOf(name: string): { set: (value: unknown) => void } {
    return (
      fixture.componentInstance as unknown as Record<string, { set: (value: unknown) => void }>
    )[name];
  }

  function lastList(): CaseListParams {
    return listCalls[listCalls.length - 1];
  }

  const bbva = policy('1', 'BBVA Seguros');
  const provincia = policy('2', 'Provincia Seguros');

  it('sends no status or insurer without filters', async () => {
    await mount([bbva]);

    expect(lastList().status).toBeUndefined();
    expect(lastList().insurerId).toBeUndefined();
    expect(lastList().insuredId).toBe('42.987.654');
  });

  it('"En trámite" expands to the four statuses it groups', async () => {
    await mount([bbva]);

    signalOf('statusFilter').set('EN_TRAMITE');
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

  it('"Terminado" sends the three final statuses', async () => {
    await mount([bbva]);

    signalOf('statusFilter').set('TERMINADO');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(lastList().status).toEqual(['APPROVED', 'REJECTED', 'LAPSED']);
  });

  it('does not offer the filter with a single insurer', async () => {
    await mount([bbva]);

    expect(fixture.nativeElement.textContent).not.toContain('Aseguradora');
  });

  it('offers it with policies in two insurers', async () => {
    await mount([bbva, provincia]);

    expect(fixture.nativeElement.textContent).toContain('Aseguradora');
  });

  it('does not repeat an insurer with several policies', async () => {
    await mount([bbva, policy('1', 'BBVA Seguros')]);

    expect(fixture.nativeElement.textContent).not.toContain('Aseguradora');
  });

  it('changing a filter goes back to the first page', async () => {
    await mount([bbva]);
    signalOf('page').set(2);
    fixture.detectChanges();
    await fixture.whenStable();

    signalOf('statusFilter').set('TERMINADO');
    (fixture.componentInstance as unknown as Record<string, () => void>)['onFilterChange']();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(lastList().page).toBe(0);
  });

  it('dates travel as the event date range', async () => {
    await mount([bbva]);

    signalOf('dateFrom').set('2026-08-01');
    signalOf('dateTo').set('2026-08-31');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(lastList().eventDateFrom).toBe('2026-08-01');
    expect(lastList().eventDateTo).toBe('2026-08-31');
  });
});
