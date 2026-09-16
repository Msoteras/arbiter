import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { BranchesService } from '../branches.service';
import { FraudReportComponent } from './fraud-report.component';
import { FraudReportService } from './fraud-report.service';
import {
  FraudReport,
  FraudReportRow,
  alertEmptyLabel,
  indicators,
  riskGaugeBand,
} from './fraud-report';
import { ReportFiltersStore } from './report-filters.store';

function row(overrides: Partial<FraudReportRow> = {}): FraudReportRow {
  return {
    caseId: 1482,
    insuredName: 'Marcos Aguirre',
    insuredDni: '28.904.115',
    branch: 'Celulares',
    claimCause: 'Robo en vía pública',
    reportedAt: '2026-09-12T09:20:00Z',
    riskBand: 'CRITICAL',
    signals: ['HIGH_RISK_SCORE', 'REPEAT_CLAIMANT', 'FORENSIC_INCONSISTENCY'],
    claimsInWindow: 3,
    suspiciousImages: 2,
    status: 'PENDING_EXPERT_REPORT',
    fraudDetermined: false,
    expertBacked: false,
    ...overrides,
  };
}

describe('fraud report helpers', () => {
  it('spells each signal out with the magnitude that makes it actionable', () => {
    expect(indicators(row())).toEqual([
      'Score de riesgo alto',
      '3 denuncias en 12 meses',
      '2 imágenes con coincidencia',
    ]);
  });

  it('keeps the singular for a single flagged image', () => {
    expect(indicators(row({ signals: ['FORENSIC_INCONSISTENCY'], suspiciousImages: 1 }))).toEqual([
      '1 imagen con coincidencia',
    ]);
  });

  /**
   * The gauge is drawn only when the score alerted. A LOW band gets no segment: a low score is not
   * an indicator of fraud, and filling one under "Nivel de alerta" would read as "nothing here"
   * about a case listed precisely because something else was found.
   */
  it('draws the gauge only for a score that alerted', () => {
    expect(riskGaugeBand(row())).toBe(4);
    expect(riskGaugeBand(row({ riskBand: 'HIGH' }))).toBe(3);
    expect(riskGaugeBand(row({ riskBand: 'LOW', signals: ['FORENSIC_INCONSISTENCY'] }))).toBeNull();
    expect(riskGaugeBand(row({ riskBand: null, signals: ['FORENSIC_INCONSISTENCY'] }))).toBeNull();
  });

  /** "No alertó" and "Sin evaluar" are different facts: the engine ran, or it never did. */
  it('tells a score that did not alert apart from a case that was never scored', () => {
    expect(alertEmptyLabel(row({ riskBand: 'LOW', signals: ['FORENSIC_INCONSISTENCY'] })))
      .toBe('No alertó');
    expect(alertEmptyLabel(row({ riskBand: null, signals: ['FORENSIC_INCONSISTENCY'] })))
      .toBe('Sin evaluar');
  });
});

describe('FraudReportComponent', () => {
  let fixture: ComponentFixture<FraudReportComponent>;

  const report: FraudReport = {
    from: '2026-09-01',
    to: '2026-09-30',
    branch: null,
    riskBand: null,
    generatedAt: '2026-09-15T15:00:00Z',
    summary: {
      totalClaims: 20,
      flagged: 2,
      flaggedRate: 0.1,
      multiSignal: 1,
      fraudDetermined: 1,
      fraudRate: 0.05,
      backedByExpert: 1,
      byAlertLevel: [
        { label: 'CRITICAL', count: 1 },
        { label: 'NOT_FLAGGED', count: 1 },
      ],
      bySignal: [
        { label: 'HIGH_RISK_SCORE', count: 1 },
        { label: 'FORENSIC_INCONSISTENCY', count: 2 },
      ],
    },
    rows: [
      row(),
      row({
        caseId: 1447,
        riskBand: null,
        signals: ['FORENSIC_INCONSISTENCY'],
        suspiciousImages: 1,
        status: 'REJECTED',
        fraudDetermined: true,
        expertBacked: true,
      }),
    ],
  };

  const reportService = {
    report: jasmine.createSpy('report').and.returnValue(of(report)),
    export: jasmine.createSpy('export'),
  };

  beforeEach(async () => {
    reportService.report.calls.reset();
    reportService.export.calls.reset();
    reportService.report.and.returnValue(of(report));
    await TestBed.configureTestingModule({
      imports: [FraudReportComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        ReportFiltersStore,
        { provide: FraudReportService, useValue: reportService },
        { provide: BranchesService, useValue: { list: () => of([{ id: 1, name: 'Celulares' }]) } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(FraudReportComponent);
    fixture.detectChanges();
  });

  function click(label: string): void {
    const button = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('app-button'),
    ).find((el) => el.textContent?.trim() === label);
    button!.querySelector('button')!.click();
    fixture.detectChanges();
  }

  function preview(): void {
    click('Ver vista previa');
  }

  it('lists the flagged cases with their labels, not the enum literals', () => {
    preview();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(reportService.report).toHaveBeenCalled();
    expect(text).toContain('Marcos Aguirre');
    expect(text).toContain('3 denuncias en 12 meses');
    expect(text).toContain('Derivado a peritaje');
    expect(text).not.toContain('PENDING_EXPERT_REPORT');
    expect(text).not.toContain('HIGH_RISK_SCORE');
  });

  it('leads with the cross and the determinations behind it', () => {
    preview();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Denuncias del período');
    expect(text).toContain('Con señales cruzadas');
    expect(text).toContain('1 con respaldo pericial');
    expect(text).toContain('Fraude determinado · pericial');
  });

  /** Una tasa sin su población es la cifra que más rápido se lee mal, así que viajan juntas. */
  it('states each rate next to the claims it was taken over', () => {
    preview();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Con indicios');
    expect(text).toContain('2 de 20');
    expect(text).toContain('1 de 20 · 1 con respaldo pericial');
  });

  /** A case with no band is still listed; calling it "Bajo" would be a different claim. */
  it('shows an unscored case as unevaluated rather than as low risk', () => {
    preview();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Sin evaluar');
  });

  /** Filtrar un reporte de fraude por "alerta = Bajo" no significa nada, así que no se ofrece. */
  it('offers only the two bands that are an alert', () => {
    expect(fixture.componentInstance['riskBandOptions'].map((o) => o.value)).toEqual([
      'HIGH',
      'CRITICAL',
    ]);
  });

  it('shows a low-scoring case as not alerted, never as "Bajo"', () => {
    reportService.report.and.returnValue(
      of({
        ...report,
        summary: {
          ...report.summary,
          flagged: 1,
          byAlertLevel: [{ label: 'NOT_FLAGGED', count: 1 }],
        },
        rows: [row({ riskBand: 'LOW', signals: ['FORENSIC_INCONSISTENCY'], suspiciousImages: 1 })],
      }),
    );

    preview();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('No alertó');
    expect(text).toContain('1 imagen con coincidencia');
    expect(text).not.toContain('Bajo');
  });

  it('sends the shared branch filter and its own band filter to the backend', () => {
    TestBed.inject(ReportFiltersStore).setBranch('1');
    fixture.componentInstance['setRiskBand']('CRITICAL');
    preview();

    expect(reportService.report).toHaveBeenCalledWith(
      jasmine.objectContaining({ branchId: 1, riskBand: 'CRITICAL' }),
    );
  });

  /** The criteria are behind the tip, not spelled out on the card: the copy is in the bubble. */
  it('keeps the detection criteria behind the info tip until it is opened', () => {
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Criterios de detección');
    expect(host.textContent).not.toContain('score de riesgo alto (banda Alto');

    host.querySelector<HTMLElement>('app-info-tip button')!.click();
    fixture.detectChanges();

    expect(host.textContent).toContain('score de riesgo alto (banda Alto');
    expect(host.textContent).toContain('El sistema no determina fraude');
  });

  it('exports the same filters it previews, in the requested format', () => {
    reportService.export.and.returnValue(
      of({ blob: new Blob(['x']), filename: 'fraude_2026-09-01_2026-09-30.csv' }),
    );
    fixture.componentInstance['setRiskBand']('CRITICAL');

    click('CSV');

    expect(reportService.export).toHaveBeenCalledWith(
      jasmine.objectContaining({ riskBand: 'CRITICAL' }),
      'CSV',
    );
  });

  it('a cleared band means every band, not the empty string as a value', () => {
    fixture.componentInstance['setRiskBand']('CRITICAL');
    fixture.componentInstance['setRiskBand']('');
    preview();

    expect(reportService.report).toHaveBeenCalledWith(jasmine.objectContaining({ riskBand: '' }));
  });
});
