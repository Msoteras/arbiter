import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, switchMap } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CaseDocumentType } from '../../core/models/case-document';
import { DocumentTypeService } from '../../core/services/document-type.service';

/**
 * Required documents per branch + claim cause, looked up by NAME (the policy and the case don't
 * carry the numeric ids). With no configured agenda or a failed call it falls back to the full
 * catalog, so the insured is never left unable to upload anything.
 */
@Injectable({ providedIn: 'root' })
export class DocumentAgendaService {
  private readonly http = inject(HttpClient);
  private readonly documentTypes = inject(DocumentTypeService);
  private readonly base = `${environment.apiBaseUrl}/rules/document-requirements`;

  /** Empty list when no agenda is configured. */
  getForBranch(branch: string, claimCause: string): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/for-branch`, { params: { branch, claimCause } });
  }

  /** Every document in the catalog, named: what is offered when no agenda applies. */
  catalogSlots(): Observable<readonly CaseDocumentType[]> {
    return this.documentTypes
      .all()
      .pipe(map((types) => types.map(({ code, label }) => ({ type: code, label }))));
  }

  /** The agenda when both are known and one is configured; otherwise the whole catalog. */
  slotsFor(
    branch: string | null,
    claimCause: string | null,
  ): Observable<readonly CaseDocumentType[]> {
    if (!branch || !claimCause) {
      return this.catalogSlots();
    }
    return this.getForBranch(branch, claimCause).pipe(
      switchMap((codes) =>
        codes.length
          ? // Waits for the catalog, so the slots carry names and not codes.
            this.catalogSlots().pipe(
              map((catalog) =>
                codes.map((type) => ({
                  type,
                  label: catalog.find((slot) => slot.type === type)?.label ?? type,
                })),
              ),
            )
          : this.catalogSlots(),
      ),
      catchError(() => this.catalogSlots()),
    );
  }
}
