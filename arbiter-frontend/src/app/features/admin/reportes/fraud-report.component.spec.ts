import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';

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
import { rememberedReportsTab } from './reports-tab-memory';

function row(overrides: Partial<FraudReportRow> = {}): FraudReportRow {
  return {
    caseId: 1482,
    insuredName: 'Marcos Aguirre',
    insuredDni: '28.904.115',
    branch: 'Celulares',
    claimCause: 'Robo en vía pública',
    reportedAt: '2026-09-12T09:20:00Z',
    riskBand: 'CRITICAL',
    signals: ['HIGH_RISK_SCORE', 'FORENSIC_INCONSISTENCY'],
    claimsInWindow: 3,
    suspiciousImages: 2,
    documentInconsistencyNote: null,
    status: 'PENDING_EXPERT_REPORT',
    fraudDetermined: false,
    expertBacked: false,
    ...overrides,
  };
}

describe('fraud report helpers', () => {
  it('spells each signal out with the magnitude that makes it actionable', () => {
    expect(indicators(row())).toEqual(['Score de riesgo alto', '2 imágenes con coincidencia']);
  });

  it('keeps the singular for a single flagged image', () => {
    expect(indicators(row({ signals: ['FORENSIC_INCONSISTENCY'], suspiciousImages: 1 }))).toEqual([
      '1 imagen con coincidencia',
    ]);
  });

  /** The document signal carries its own rationale verbatim — it already says what didn't match. */
  it('shows the document signal as its own rationale, not a generic label', () => {
    expect(
      indicators(
        row({
          signals: ['DOCUMENT_INCONSISTENCY'],
          documentInconsistencyNote: 'El importe del documento no coincide con el reclamado',
        }),
      ),
    ).toEqual(['El importe del documento no coincide con el reclamado']);
  });

  /**
   * The gauge is drawn only when the score alerted. A LOW band gets no segment: a low score is not
   * an indicator of fraud, and filling one under "Score de riesgo" would read as "nothing here"
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
    expect(alertEmptyLabel(row({ riskBand: 'LOW', signals: ['FORENSIC_INCONSISTENCY'] }))).toBe(
      'No alertó',
    );
    expect(alertEmptyLabel(row({ riskBand: null, signals: ['FORENSIC_INCONSISTENCY'] }))).toBe(
      'Sin evaluar',
    );
  });
});

describe('FraudReportComponent', () => {
  registerLocaleData(localeEsAr);
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
    previousSummary: {
      totalClaims: 15,
      flagged: 3,
      flaggedRate: 0.2,
      multiSignal: 0,
      fraudDetermined: 0,
      fraudRate: null,
      backedByExpert: 0,
      byAlertLevel: [],
      bySignal: [],
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
        // The app runs in es-AR (app.config); TestBed doesn't read that config.
        { provide: LOCALE_ID, useValue: 'es-AR' },
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
    // Contexto de la fila, no una señal: se muestra bajo el asegurado.
    expect(text).toContain('3 denuncias en 12 meses');
    expect(text).toContain('2 imágenes con coincidencia');
    expect(text).toContain('Derivado a peritaje');
    expect(text).not.toContain('PENDING_EXPERT_REPORT');
    expect(text).not.toContain('HIGH_RISK_SCORE');
  });

  /** Lo que ordena la tabla tiene que verse en la tabla, o el orden parece un desorden. */
  it('marks the rows whose signals coincide, and says how it sorted them', () => {
    preview();

    const host = fixture.nativeElement as HTMLElement;
    const rows = Array.from(host.querySelectorAll('tbody tr'));
    expect(rows[0].classList).toContain('multi-signal');
    // Una sola señal no es un cruce: sin marca.
    expect(rows[1].classList).not.toContain('multi-signal');
    expect(host.textContent).toContain('Ordenadas por cantidad de señales');
  });

  /** With only two possible signals a count badge would always read "2" — nothing worth a badge. */
  it('does not badge the signal count, only the signals themselves', () => {
    preview();

    const host = fixture.nativeElement as HTMLElement;
    const rows = Array.from(host.querySelectorAll('tbody tr'));
    expect(rows[0].textContent).not.toContain('señales');
    expect(rows[0].textContent).toContain('Score de riesgo alto');
    expect(rows[0].textContent).toContain('2 imágenes con coincidencia');
  });

  it('leads with the cross and the determinations behind it', () => {
    preview();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Denuncias del período');
    expect(text).toContain('Con dos o más señales');
    expect(text).toContain('1 con respaldo pericial');
    expect(text).toContain('Fraude determinado · pericial');
  });

  /**
   * No verdict on these three: more or fewer claims, or a higher or lower flagged share, isn't
   * better or worse news on its own — see the component's own comments on why.
   */
  it('compares the KPI cards against the previous period, without a verdict', () => {
    preview();

    const text = (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('▲ 5 vs. el período anterior');
    expect(text).toContain('▼ 10% vs. el período anterior');
    expect(text).toContain('▲ 1 vs. el período anterior');
  });

  /**
   * Below the minimum base a rate can swing ten points on one case — not a trend, just noise. The
   * raw count above isn't gated the same way: a "+18 denuncias" delta isn't noisy just because the
   * previous period was thin, same criterion the dashboard's own funnel uses.
   */
  it('drops the rate and cross-count comparisons when the previous period is too thin', () => {
    reportService.report.and.returnValue(
      of({ ...report, previousSummary: { ...report.previousSummary, totalClaims: 2 } }),
    );

    preview();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('▼ 10% vs. el período anterior');
    expect(text).not.toContain('▲ 1 vs. el período anterior');
    expect(text).toContain('▲ 18 vs. el período anterior');
  });

  /** A rate without its population is the figure people misread fastest, so they travel together. */
  it('states each rate next to the claims it was taken over', () => {
    // Previous period too thin to compare against, so the trend doesn't take the sub's place.
    reportService.report.and.returnValue(
      of({ ...report, previousSummary: { ...report.previousSummary, totalClaims: 2 } }),
    );

    preview();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Con al menos una señal');
    expect(text).toContain('2 de 20');
    expect(text).toContain('1 de 20 · 1 con respaldo pericial');
  });

  /**
   * "0%" bajo "Fraude determinado" no dice que no haya indicios: dice que todavía nadie determinó
   * ninguno. Sin la aclaración al lado, la cifra se lee como lo contrario de lo que significa.
   */
  it('explains what each figure counts, and does not paint a zero as an alert', () => {
    reportService.report.and.returnValue(
      of({ ...report, summary: { ...report.summary, fraudDetermined: 0, fraudRate: 0 } }),
    );

    preview();

    const host = fixture.nativeElement as HTMLElement;
    const determinado = Array.from(host.querySelectorAll('app-stat-tile')).find((tile) =>
      tile.textContent?.includes('Fraude determinado'),
    )!;
    expect(determinado.querySelector('app-info-tip')).not.toBeNull();
    // El tono de alerta se reserva para cuando hay algo determinado.
    expect(determinado.querySelector('.stat.danger')).toBeNull();

    determinado.querySelector<HTMLElement>('app-info-tip button')!.click();
    fixture.detectChanges();
    expect(determinado.textContent).toContain('El sistema no determina fraude');
  });

  it('paints the determination as an alert once there is one', () => {
    preview();

    const host = fixture.nativeElement as HTMLElement;
    const determinado = Array.from(host.querySelectorAll('app-stat-tile')).find((tile) =>
      tile.textContent?.includes('Fraude determinado'),
    )!;
    expect(determinado.querySelector('.stat.danger')).not.toBeNull();
  });

  /** A case with no band is still listed; calling it "Bajo" would be a different claim. */
  it('shows an unscored case as unevaluated rather than as low risk', () => {
    preview();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Sin evaluar');
  });

  /** Filtering a fraud report by "alert = Low" means nothing, so it isn't offered. */
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

  it('formats the rates with the es-AR decimal comma', () => {
    reportService.report.and.returnValue(
      of({ ...report, summary: { ...report.summary, flaggedRate: 1 / 7, fraudRate: 0.0357 } }),
    );

    preview();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('14,3');
    expect(text).toContain('3,6');
    expect(text).not.toContain('14.3');
  });

  /** A case with two signals counts in both, so each share is over the flagged cases. */
  it('shows which signals fired, as shares of the flagged cases', () => {
    preview();

    const host = fixture.nativeElement as HTMLElement;
    const signals = Array.from(host.querySelectorAll('.distribution')).find((el) =>
      el.textContent?.includes('Por señal'),
    )!;
    const legend = Array.from(signals.querySelectorAll('.legend li')).map((li) =>
      Array.from(li.querySelectorAll('span'))
        .map((span) => span.textContent!.trim())
        .filter(Boolean)
        .join(' | '),
    );
    expect(legend).toEqual(['Score de riesgo alto | 1 | 50%', 'Incoherencias forenses | 2 | 100%']);
    // Overlapping buckets can't be stacked into one bar.
    expect(signals.querySelector('.stack')).toBeNull();
  });

  it('with claims but none flagged, says so without restating the period total', () => {
    reportService.report.and.returnValue(
      of({ ...report, summary: { ...report.summary, flagged: 0, flaggedRate: 0 }, rows: [] }),
    );

    preview();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Ninguna denuncia con señales de fraude en el período');
    expect(text).toContain('Ninguna de las denuncias del período disparó una señal de fraude');
  });

  it('with no claims at all, says the period is empty', () => {
    reportService.report.and.returnValue(
      of({
        ...report,
        summary: { ...report.summary, totalClaims: 0, flagged: 0, flaggedRate: null },
        rows: [],
      }),
    );

    preview();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'No hay denuncias en el período',
    );
  });

  /** The response of a request whose filters already changed must not land under the new ones. */
  it('drops a preview still in flight when a shared filter changes', () => {
    const response = new Subject<FraudReport>();
    reportService.report.and.returnValue(response);

    preview();
    TestBed.inject(ReportFiltersStore).from.set('2026-09-05');
    fixture.detectChanges();
    response.next(report);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('Marcos Aguirre');
    expect(text).toContain('Todavía no hay vista previa');
  });

  it('a cleared band means every band, not the empty string as a value', () => {
    fixture.componentInstance['setRiskBand']('CRITICAL');
    fixture.componentInstance['setRiskBand']('');
    preview();

    expect(reportService.report).toHaveBeenCalledWith(jasmine.objectContaining({ riskBand: '' }));
  });
});

describe('FraudReportComponent opened from a link', () => {
  function open(query: Record<string, string>) {
    const report = jasmine.createSpy('report').and.returnValue(
      of({
        from: '2026-09-01',
        to: '2026-09-30',
        branch: null,
        riskBand: 'CRITICAL',
        generatedAt: '2026-09-15T15:00:00Z',
        summary: {
          totalClaims: 0,
          flagged: 0,
          flaggedRate: null,
          multiSignal: 0,
          fraudDetermined: 0,
          fraudRate: null,
          backedByExpert: 0,
          byAlertLevel: [],
          bySignal: [],
        },
        previousSummary: {
          totalClaims: 0,
          flagged: 0,
          flaggedRate: null,
          multiSignal: 0,
          fraudDetermined: 0,
          fraudRate: null,
          backedByExpert: 0,
          byAlertLevel: [],
          bySignal: [],
        },
        rows: [],
      } satisfies FraudReport),
    );
    TestBed.configureTestingModule({
      imports: [FraudReportComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: convertToParamMap(query), routeConfig: { path: 'fraud' } },
          },
        },
        ReportFiltersStore,
        { provide: FraudReportService, useValue: { report, export: jasmine.createSpy() } },
        { provide: BranchesService, useValue: { list: () => of([]) } },
      ],
    });
    TestBed.inject(ReportFiltersStore).hydrate(convertToParamMap(query));
    const fixture = TestBed.createComponent(FraudReportComponent);
    fixture.detectChanges();
    return { fixture, report };
  }

  it('previews right away, with the alert level the link carried', () => {
    const { fixture, report } = open({
      from: '2026-09-01',
      to: '2026-09-30',
      riskBand: 'CRITICAL',
    });

    expect(report).toHaveBeenCalledOnceWith(jasmine.objectContaining({ riskBand: 'CRITICAL' }));
    const text = (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('Ramo: Todos · Score de riesgo: Crítico');
  });

  /** Volver a Reportes desde el menú abre la solapa que se estaba usando, no siempre la primera. */
  it('is remembered as the tab to open next time', () => {
    localStorage.removeItem('arbiter.reports.tab');

    open({ from: '2026-09-01', to: '2026-09-30' });

    expect(rememberedReportsTab()).toBe('fraud');
    localStorage.removeItem('arbiter.reports.tab');
  });

  it('ignores an alert level the filter does not offer', () => {
    const { report } = open({ from: '2026-09-01', to: '2026-09-30', riskBand: 'LOW' });

    expect(report).toHaveBeenCalledOnceWith(jasmine.objectContaining({ riskBand: '' }));
  });
});
