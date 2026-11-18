import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { CaseDocumentsComponent } from './case-documents.component';
import { DocumentAnalysis, DocumentCorrection } from '../../../core/models/case';
import { CaseDocument } from '../../../core/models/case-document';
import { CaseService } from '../case.service';
import { DocumentAgendaService } from '../document-agenda.service';
import {
  DOCUMENT_TYPES_FIXTURE,
  provideDocumentTypesStub,
} from '../../../core/testing/document-types';

/** A provider's report is the insurer's: the portal never lists it, even if the backend sent it. */
describe('CaseDocumentsComponent · provider reports', () => {
  let fixture: ComponentFixture<CaseDocumentsComponent>;

  const doc = (id: number, type: string): CaseDocument => ({
    id,
    type,
    filename: `${type}.pdf`,
    contentType: 'application/pdf',
    sizeBytes: 1024,
    uploadedAt: '2026-10-09T12:00:00Z',
    description: null,
  });

  async function mount(showMissing: boolean, focusType: string | null = null): Promise<string> {
    await TestBed.configureTestingModule({
      imports: [CaseDocumentsComponent],
      providers: [
        provideDocumentTypesStub(),
        {
          provide: CaseService,
          useValue: {
            listDocuments: () =>
              of([doc(1, 'police_report'), doc(2, 'expert_report'), doc(3, 'repair_report')]),
            downloadDocument: () => of(new Blob(['x'], { type: 'application/pdf' })),
          },
        },
        {
          provide: DocumentAgendaService,
          // No branch given: the whole catalog, as the real service answers.
          useValue: {
            slotsFor: () =>
              of(DOCUMENT_TYPES_FIXTURE.map(({ code, label }) => ({ type: code, label }))),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CaseDocumentsComponent);
    fixture.componentRef.setInput('caseId', 37);
    fixture.componentRef.setInput('showMissing', showMissing);
    fixture.componentRef.setInput('focusType', focusType);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('the portal lists only what the insured sent', async () => {
    const text = await mount(false);

    expect(text).toContain('Denuncia policial');
    expect(text).toContain('1 documento enviado');
    expect(text).not.toContain('Informe pericial');
    expect(text).not.toContain('Respuesta del servicio técnico');
    expect(text).not.toContain('expert_report');
    expect(text).not.toContain('repair_report');
  });

  it('the analyst sees them apart from the document agenda', async () => {
    const text = await mount(true);

    expect(text).toContain('Otros documentos del expediente');
    expect(text).toContain('Informe pericial');
    expect(text).toContain('Respuesta del servicio técnico');
  });

  /** A link in the model's reasons opens that document, not the first one. */
  const selectedId = (): number | null =>
    (fixture.componentInstance as unknown as { selectedId: () => number | null }).selectedId();

  it('opens the focused document', async () => {
    await mount(true, 'expert_report');

    expect(selectedId()).toBe(2);
  });

  it('opens the first document when none is focused', async () => {
    await mount(true);

    expect(selectedId()).toBe(1);
  });
});

describe('CaseDocumentsComponent · corrections', () => {
  let fixture: ComponentFixture<CaseDocumentsComponent>;
  let correctDocument: jasmine.Spy;

  const quote: CaseDocument = {
    id: 90,
    type: 'repair_quote',
    filename: 'presupuesto.pdf',
    contentType: 'application/pdf',
    sizeBytes: 1024,
    uploadedAt: '2026-11-10T12:00:00Z',
    description: null,
  };

  const correction = (
    previousValue: string | null,
    newValue: string,
    correctedAt: string,
  ): DocumentCorrection => ({
    field: 'AMOUNT',
    fieldName: null,
    previousValue,
    newValue,
    analystName: 'Lucas Gómez',
    correctedAt,
  });

  const reading = (corrections: DocumentCorrection[]): DocumentAnalysis => ({
    documentType: 'repair_quote',
    documentId: 90,
    transcription: 'Presupuesto…',
    documentDate: null,
    amount: 120000,
    itemDescription: 'Samsung A55',
    itemAttributes: [{ name: 'IMEI', value: '356789012345670' }],
    affectedParty: 'TITULAR',
    visualFindings: [],
    details: [],
    extractionStatus: 'COMPLETE',
    corrections,
  });

  const money = (amount: number): string => `$${amount.toLocaleString()}`;

  async function mount(canCorrect: boolean, corrections: DocumentCorrection[] = []): Promise<void> {
    correctDocument = jasmine.createSpy('correctDocument');
    await TestBed.configureTestingModule({
      imports: [CaseDocumentsComponent],
      providers: [
        provideDocumentTypesStub(),
        {
          provide: CaseService,
          useValue: {
            listDocuments: () => of([quote]),
            downloadDocument: () => of(new Blob(['x'], { type: 'application/pdf' })),
            correctDocument,
          },
        },
        {
          provide: DocumentAgendaService,
          useValue: {
            slotsFor: () =>
              of(DOCUMENT_TYPES_FIXTURE.map(({ code, label }) => ({ type: code, label }))),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CaseDocumentsComponent);
    fixture.componentRef.setInput('caseId', 37);
    fixture.componentRef.setInput('extractions', [reading(corrections)]);
    fixture.componentRef.setInput('canCorrect', canCorrect);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const text = (): string => (fixture.nativeElement as HTMLElement).textContent ?? '';

  const editButton = (label: string): HTMLButtonElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`button[aria-label="Corregir ${label}"]`);

  const component = (): { draft: { set: (value: string) => void } } =>
    fixture.componentInstance as unknown as { draft: { set: (value: string) => void } };

  const buttonWithText = (label: string): HTMLButtonElement =>
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === label,
    ) as HTMLButtonElement;

  it('shows the corrected value next to what Arbiter read, with who and when', async () => {
    await mount(false, [
      correction('120000.00', '100000.00', '2026-11-17T12:00:00Z'),
      correction('100000.00', '95000.00', '2026-11-17T13:00:00Z'),
    ]);

    expect(text()).toContain('Corregido');
    expect(text()).toContain(money(95000));
    expect(text()).toContain(`Arbiter leyó ${money(120000)}`);
    expect(text()).toContain('Lucas Gómez');
    expect(text()).toContain('Ver las correcciones (2)');
    expect(text()).toContain(`${money(100000)} → ${money(95000)}`);
    expect(text()).toContain(`${money(120000)} → ${money(100000)}`);
  });

  it('offers no correction without permission', async () => {
    await mount(false);

    expect(editButton('Importe')).toBeNull();
    expect(text()).not.toContain('Corregido');
  });

  it('offers to correct the typed fields and the item attributes, not the affected party', async () => {
    await mount(true);

    expect(editButton('Fecha del documento')).not.toBeNull();
    expect(editButton('Importe')).not.toBeNull();
    expect(editButton('Bien que nombra')).not.toBeNull();
    expect(editButton('IMEI')).not.toBeNull();
    expect(editButton('Damnificado')).toBeNull();
  });

  it('saves the correction and shows it before the case reloads', async () => {
    await mount(true);
    correctDocument.and.returnValue(of(correction('120000', '95000.00', '2026-11-17T13:00:00Z')));
    const corrected = jasmine.createSpy('corrected');
    fixture.componentInstance.corrected.subscribe(corrected);

    editButton('Importe')!.click();
    fixture.detectChanges();
    component().draft.set('95000');
    fixture.detectChanges();
    buttonWithText('Guardar').click();
    fixture.detectChanges();

    expect(correctDocument).toHaveBeenCalledWith(37, 90, 'AMOUNT', null, '95000');
    expect(corrected).toHaveBeenCalled();
    expect(text()).toContain('Corregido');
    expect(text()).toContain(money(95000));
  });

  it('shows why the backend refused it', async () => {
    await mount(true);
    correctDocument.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 422,
            error: { detail: 'El documento ya tiene ese dato.' },
          }),
      ),
    );

    editButton('Importe')!.click();
    fixture.detectChanges();
    buttonWithText('Guardar').click();
    fixture.detectChanges();

    expect(text()).toContain('El documento ya tiene ese dato.');
    expect(buttonWithText('Cancelar')).toBeDefined();
  });
});
