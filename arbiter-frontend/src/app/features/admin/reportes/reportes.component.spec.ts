import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { ExpedienteService } from '../../expedientes/expediente.service';
import { ReportesComponent } from './reportes.component';
import { ResolutionReport, decisionLabel, formatDuration, periodError } from './resolution-report';
import { ResolutionReportService } from './resolution-report.service';

describe('resolution report helpers', () => {
  it('formats durations the same way the exported PDF does', () => {
    expect(formatDuration(45)).toBe('45 min');
    expect(formatDuration(60)).toBe('1 h');
    expect(formatDuration(200)).toBe('3 h 20 min');
    expect(formatDuration(1440)).toBe('1 d');
    expect(formatDuration(3030)).toBe('2 d 2 h');
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

describe('ReportesComponent', () => {
  let fixture: ComponentFixture<ReportesComponent>;

  const report: ResolutionReport = {
    from: '2026-08-01',
    to: '2026-08-31',
    claimCause: null,
    generatedAt: '2026-09-11T15:00:00Z',
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
    await TestBed.configureTestingModule({
      imports: [ReportesComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: ResolutionReportService, useValue: reportService },
        { provide: ExpedienteService, useValue: { claimCauseNames: () => of(['Hurto']) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ReportesComponent);
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
});
