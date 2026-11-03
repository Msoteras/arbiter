import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { CaseDocumentsComponent } from './case-documents.component';
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

  async function mount(showMissing: boolean): Promise<string> {
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
});
