import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { environment } from '../../../environments/environment';
import { DocumentTypeService } from './document-type.service';

describe('DocumentTypeService', () => {
  const url = `${environment.apiBaseUrl}/rules/document-types`;
  let service: DocumentTypeService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(DocumentTypeService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function answerCatalog(): void {
    http.expectOne(url).flush([
      { code: 'police_report', name: 'Denuncia policial' },
      { code: 'item_photo', name: 'Foto del bien' },
    ]);
  }

  it('names a document with the catalog', () => {
    answerCatalog();

    expect(service.label('police_report')).toBe('Denuncia policial');
    expect(service.withLabels('Falta: police_report, item_photo')).toBe(
      'Falta: Denuncia policial, Foto del bien',
    );
  });

  it('names the providers reports, which are not in the catalog', () => {
    answerCatalog();

    expect(service.label('expert_report')).toBe('Informe pericial');
  });

  it('shows the code of a document it has no name for', () => {
    answerCatalog();

    expect(service.label('warranty_card')).toBe('warranty_card');
  });

  it('fetches the catalog once for the whole session', async () => {
    answerCatalog();

    const all = await firstValueFrom(service.all());

    expect(all.map((d) => d.label)).toEqual(['Denuncia policial', 'Foto del bien']);
    http.expectNone(url);
  });

  it('asks for one branch by id', async () => {
    answerCatalog();
    const branch = firstValueFrom(service.forBranch(2));

    http
      .expectOne((req) => req.url === url && req.params.get('branchId') === '2')
      .flush([{ code: 'item_photo', name: 'Foto del bien' }]);

    expect(await branch).toEqual([{ code: 'item_photo', label: 'Foto del bien' }]);
  });
});
