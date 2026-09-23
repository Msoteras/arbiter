import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  CASE_DOCUMENT_TYPES,
  CaseDocumentType,
  documentTypeLabel,
} from '../../core/models/case-document';

/**
 * Required documents per branch + claim cause, looked up by NAME (the policy and the case don't
 * carry the numeric ids). With no configured agenda or a failed call it falls back to the full
 * catalog, so the insured is never left unable to upload anything.
 */
@Injectable({ providedIn: 'root' })
export class DocumentAgendaService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/document-requirements`;

  /** Empty list when no agenda is configured. */
  getForBranch(branch: string, claimCause: string): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/for-branch`, { params: { branch, claimCause } });
  }

  slotsForBranch(branch: string, claimCause: string): Observable<readonly CaseDocumentType[]> {
    return this.getForBranch(branch, claimCause).pipe(
      map((codes) =>
        codes.length
          ? codes.map((type) => ({ type, label: documentTypeLabel(type) }))
          : CASE_DOCUMENT_TYPES,
      ),
      catchError(() => of(CASE_DOCUMENT_TYPES)),
    );
  }
}
