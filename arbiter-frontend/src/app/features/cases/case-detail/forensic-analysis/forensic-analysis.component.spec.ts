import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { ForensicAnalysisComponent } from './forensic-analysis.component';
import { CaseService } from '../../case.service';
import { ImageForensicFinding, ImageForensicReport } from '../../../../core/models/forensic';

describe('ForensicAnalysisComponent · internal comparison', () => {
  let fixture: ComponentFixture<ForensicAnalysisComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ForensicAnalysisComponent],
      providers: [
        {
          provide: CaseService,
          useValue: { listDocuments: () => of([]), downloadDocument: () => of(new Blob()) },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ForensicAnalysisComponent);
    fixture.componentRef.setInput('caseId', 49);
  });

  function render(finding: Partial<ImageForensicFinding>): string {
    const report: ImageForensicReport = {
      imagesAnalyzed: 1,
      webSearchesPerformed: 0,
      findings: [
        {
          label: 'item_photo-0',
          documentType: 'item_photo',
          internalMatches: [],
          webFinding: null,
          ...finding,
        },
      ],
    };
    fixture.componentRef.setInput('report', report);
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('says the image was not compared when the internal check failed', () => {
    const text = render({ internalCheckFailed: true });

    expect(text).toContain('No se pudo comparar con adjuntos de siniestros previos.');
    expect(text).not.toContain('Sin coincidencias');
  });

  it('reports a clean image when the comparison ran and found nothing', () => {
    const text = render({ internalCheckFailed: false });

    expect(text).toContain('Sin coincidencias');
    expect(text).not.toContain('No se pudo comparar');
  });

  it('reads a report stored before the field existed as compared', () => {
    const text = render({});

    expect(text).toContain('Sin coincidencias');
    expect(text).not.toContain('No se pudo comparar');
  });
});
