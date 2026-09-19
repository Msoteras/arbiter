import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';

import { ExpedienteService } from '../../expedientes/expediente.service';
import { BranchesService } from '../branches.service';
import { ReportFiltersStore } from './report-filters.store';
import { ResolutionReportComponent } from './resolution-report.component';
import {
  ResolutionReport,
  decisionLabel,
  formatDuration,
  periodError,
  waitingBreakdown,
} from './resolution-report';
import { ResolutionReportService } from './resolution-report.service';

describe('resolution report helpers', () => {
  it('formats durations the same way the exported PDF does', () => {
    expect(formatDuration(45)).toBe('45 min');
    expect(formatDuration(60)).toBe('1 h');
    expect(formatDuration(200)).toBe('3 h 20 min');
    expect(formatDuration(1440)).toBe('1 d');
    expect(formatDuration(3030)).toBe('2 d 2 h');
  });

  /** The summary's average is a fraction; the PDF rounds it, and the screen has to say the same. */
  it('rounds a fractional average instead of printing its decimals', () => {
    expect(formatDuration(45.4)).toBe('45 min');
    expect(formatDuration(200.333)).toBe('3 h 20 min');
    expect(formatDuration(59.6)).toBe('1 h');
  });

  /** Same split and the same wording the dashboard uses under its own average. */
  it('splits the average into the insurer own time and the wait on a third party', () => {
    const summary = { averageMinutes: 3030, averageWaitingMinutes: 600 } as never;

    expect(waitingBreakdown(summary)).toBe('1 d 16 h de gestión · 10 h esperando a terceros');
  });

  /** Under an hour it isn't a wait, it's a case passing through a status while somebody moved it. */
  it('says nothing about a wait of minutes, or about an unknown average', () => {
    expect(waitingBreakdown({ averageMinutes: 3030, averageWaitingMinutes: 12 } as never)).toBe('');
    expect(waitingBreakdown({ averageMinutes: null, averageWaitingMinutes: null } as never)).toBe(
      '',
    );
  });

  it('labels both spellings of a decision, and only a missing one as absent', () => {
    expect(decisionLabel('APPROVE')).toBe('Aprobó');
    // Older rows keep the Spanish spelling: showing them as "Sin decisión" would hide a real verdict.
    expect(decisionLabel('RECHAZAR')).toBe('Rechazó');
    expect(decisionLabel('APROBAR')).toBe('Aprobó');
    expect(decisionLabel(null)).toBe('Sin decisión');
  });

  it('rejects incomplete, inverted and over-a-year periods, and accepts a leap year', () => {
    expect(periodError('', '2026-08-31')).not.toBeNull();
    expect(periodError('2026-09-01', '2026-08-31')).not.toBeNull();
    expect(periodError('2025-01-01', '2026-01-02')).not.toBeNull();
    expect(periodError('2024-01-01', '2024-12-31')).toBeNull();
    expect(periodError('2026-08-15', '2026-08-15')).toBeNull();
  });
});

describe('ResolutionReportComponent', () => {
  registerLocaleData(localeEsAr);
  let fixture: ComponentFixture<ResolutionReportComponent>;

  const report: ResolutionReport = {
    from: '2026-08-01',
    to: '2026-08-31',
    branch: null,
    claimCause: null,
    generatedAt: '2026-09-11T15:00:00Z',
    summary: {
      totalCases: 4,
      decidedCases: 3,
      averageMinutes: 3030,
      averageWaitingMinutes: 600,
      fastTrackCases: 1,
      fastTrackRate: 0.25,
      byStatus: [
        { label: 'APPROVED', count: 3 },
        { label: 'LAPSED', count: 1 },
      ],
      byClaimCause: [{ label: 'Robo en vía pública', count: 4 }],
    },
    rows: [
      {
        caseId: 42,
        insuredName: 'Ana Pérez',
        insuredDni: '30.111.222',
        branch: 'Celulares',
        claimCause: 'Robo en vía pública',
        reportedAt: '2026-08-01T10:00:00Z',
        resolvedAt: '2026-08-03T12:30:00Z',
        totalMinutes: 3030,
        waitingMinutes: 600,
        classification: 'LLM_RECOMIENDA_APROBAR',
        analystDecision: 'APPROVE',
        finalStatus: 'APPROVED',
        analystName: 'Laura Gómez',
      },
    ],
  };

  const reportService = {
    preview: jasmine.createSpy('preview').and.returnValue(of(report)),
    export: jasmine.createSpy('export'),
  };

  beforeEach(async () => {
    reportService.preview.calls.reset();
    reportService.preview.and.returnValue(of(report));
    await TestBed.configureTestingModule({
      imports: [ResolutionReportComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        // The app runs in es-AR (app.config); TestBed doesn't read that config.
        { provide: LOCALE_ID, useValue: 'es-AR' },
        ReportFiltersStore,
        { provide: ResolutionReportService, useValue: reportService },
        { provide: ExpedienteService, useValue: { claimCauseNames: () => of(['Hurto']) } },
        { provide: BranchesService, useValue: { list: () => of([{ id: 1, name: 'Celulares' }]) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ResolutionReportComponent);
    fixture.detectChanges();
  });

  function click(label: string): void {
    const button = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('app-button'),
    ).find((el) => el.textContent?.trim() === label);
    button!.querySelector('button')!.click();
    fixture.detectChanges();
  }

  it('previews the rows with their labels, not the enum literals', () => {
    click('Ver vista previa');

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(reportService.preview).toHaveBeenCalled();
    expect(text).toContain('Ana Pérez');
    expect(text).toContain('2 d 2 h');
    expect(text).toContain('Recomienda aprobar');
    expect(text).toContain('Aprobado');
    expect(text).not.toContain('LLM_RECOMIENDA_APROBAR');
  });

  /** The average covers the decided ones only, so the screen says which ones those are. */
  it('shows the population of the average and how it splits', () => {
    click('Ver vista previa');

    const text = (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('3 decididos · 1 caducados');
    expect(text).toContain('de gestión · 10 h esperando a terceros');
  });

  /** H0019: the four figures, taken from the backend rather than added up on screen. */
  it('shows the totals of the period, with the statuses labelled in Spanish', () => {
    click('Ver vista previa');

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Expedientes resueltos');
    expect(text).toContain('Tiempo promedio de resolución');
    // 0.25 through the rate pipe: es-AR decimal comma, no space before the sign.
    expect(text).toContain('25%');
    expect(text).toContain('1 de 4');
    expect(text).toContain('Caducado');
    expect(text).not.toContain('LAPSED');
  });

  /** The response of a request whose filters already changed must not land under the new ones. */
  it('drops a preview still in flight when a filter changes', () => {
    const response = new Subject<ResolutionReport>();
    reportService.preview.and.returnValue(response);

    click('Ver vista previa');
    TestBed.inject(ReportFiltersStore).from.set('2026-08-05');
    fixture.detectChanges();
    response.next(report);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('Ana Pérez');
    expect(text).toContain('Todavía no hay vista previa');
    reportService.preview.and.returnValue(of(report));
  });

  it('sends the branch filter to the backend', () => {
    TestBed.inject(ReportFiltersStore).setBranch('1');
    click('Ver vista previa');

    expect(reportService.preview).toHaveBeenCalledWith(jasmine.objectContaining({ branchId: 1 }));
  });

  it('a cleared branch means every branch, not branch zero', () => {
    TestBed.inject(ReportFiltersStore).setBranch('1');
    TestBed.inject(ReportFiltersStore).setBranch('');
    click('Ver vista previa');

    expect(reportService.preview).toHaveBeenCalledWith(
      jasmine.objectContaining({ branchId: null }),
    );
  });
});

describe('ResolutionReportComponent opened from a link', () => {
  const report = {
    from: '2026-08-01',
    to: '2026-08-31',
    branch: 'Celulares',
    claimCause: 'Hurto',
    generatedAt: '2026-09-11T15:00:00Z',
    summary: {
      totalCases: 0,
      decidedCases: 0,
      averageMinutes: null,
      averageWaitingMinutes: null,
      fastTrackCases: 0,
      fastTrackRate: null,
      byStatus: [],
      byClaimCause: [],
    },
    rows: [],
  } satisfies ResolutionReport;

  function open(query: Record<string, string>, causes: string[] = ['Hurto']) {
    const preview = jasmine.createSpy('preview').and.returnValue(of(report));
    TestBed.configureTestingModule({
      imports: [ResolutionReportComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(query) } },
        },
        ReportFiltersStore,
        { provide: ResolutionReportService, useValue: { preview, export: jasmine.createSpy() } },
        { provide: ExpedienteService, useValue: { claimCauseNames: () => of(causes) } },
        { provide: BranchesService, useValue: { list: () => of([{ id: 1, name: 'Celulares' }]) } },
      ],
    });
    // The shell hydrates the store before the tab is created.
    TestBed.inject(ReportFiltersStore).hydrate(convertToParamMap(query));
    const fixture = TestBed.createComponent(ResolutionReportComponent);
    fixture.detectChanges();
    return { fixture, preview };
  }

  it('previews right away, with the claim cause the link carried', () => {
    const { fixture, preview } = open({
      from: '2026-08-01',
      to: '2026-08-31',
      branchId: '1',
      claimCause: 'Hurto',
    });

    expect(preview).toHaveBeenCalledOnceWith(
      jasmine.objectContaining({ from: '2026-08-01', branchId: 1, claimCause: 'Hurto' }),
    );
    // The filters as the backend applied them, above the result.
    const text = (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('Ramo: Celulares · Tipo de siniestro: Hurto');
  });

  it('writes its own filter into the link the shell builds', () => {
    open({ from: '2026-08-01', to: '2026-08-31', claimCause: 'Hurto' });

    expect(TestBed.inject(ReportFiltersStore).asQueryParams()).toEqual(
      jasmine.objectContaining({ claimCause: 'Hurto' }),
    );
  });

  /** Reached from the menu: the screen opens with its report, same as from a link. */
  it('previews on entry even when the URL carries nothing', () => {
    const { preview } = open({});

    expect(preview).toHaveBeenCalledTimes(1);
  });

  /** A cause the catalog doesn't list still shows as selected, not as "Todos". */
  it('keeps a linked claim cause the catalog does not list visible in the select', () => {
    const { fixture } = open({ from: '2026-08-01', to: '2026-08-31', claimCause: 'Granizo' }, []);

    expect(fixture.componentInstance['claimCauseOptions']()).toEqual([
      { value: 'Granizo', label: 'Granizo' },
    ]);
  });
});
