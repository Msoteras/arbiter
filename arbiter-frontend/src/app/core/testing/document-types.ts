import { Provider } from '@angular/core';
import { Observable, of } from 'rxjs';

import {
  DocumentTypeDef,
  NON_AGENDA_DOCUMENT_TYPES,
  withDocumentLabels,
} from '../models/business-rules';
import { DocumentTypeService } from '../services/document-type.service';

/** The catalog as `db/init-multitenant.sql` loads it. */
export const DOCUMENT_TYPES_FIXTURE: readonly DocumentTypeDef[] = [
  { code: 'police_report', label: 'Denuncia policial' },
  { code: 'purchase_proof', label: 'Comprobante de compra' },
  { code: 'imei_deregistration', label: 'Baja de IMEI (ENACOM)' },
  { code: 'last_connection', label: 'Captura de última conexión' },
  { code: 'repair_quote', label: 'Presupuesto de reparación' },
  { code: 'item_photo', label: 'Foto del bien' },
];

/** Catalog plus the providers' reports, as the service names them. */
export const NAMED_DOCUMENT_TYPES_FIXTURE: readonly DocumentTypeDef[] = [
  ...DOCUMENT_TYPES_FIXTURE,
  ...NON_AGENDA_DOCUMENT_TYPES,
];

/** Answers from the fixture without HTTP; `forBranch` gives every branch the whole catalog. */
export class DocumentTypeServiceStub implements Pick<
  DocumentTypeService,
  'all' | 'forBranch' | 'label' | 'withLabels' | 'definitions'
> {
  all(): Observable<readonly DocumentTypeDef[]> {
    return of(DOCUMENT_TYPES_FIXTURE);
  }

  forBranch(): Observable<readonly DocumentTypeDef[]> {
    return of(DOCUMENT_TYPES_FIXTURE);
  }

  label(code: string): string {
    return NAMED_DOCUMENT_TYPES_FIXTURE.find((d) => d.code === code)?.label ?? code;
  }

  withLabels(text: string): string {
    return withDocumentLabels(text, NAMED_DOCUMENT_TYPES_FIXTURE);
  }

  definitions(): readonly DocumentTypeDef[] {
    return NAMED_DOCUMENT_TYPES_FIXTURE;
  }
}

export function provideDocumentTypesStub(): Provider {
  return { provide: DocumentTypeService, useClass: DocumentTypeServiceStub };
}
