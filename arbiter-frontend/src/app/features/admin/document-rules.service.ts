import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

export interface DocumentRequirementDto {
  id: number;
  documentType: string;
  claimCauseId: number;
  mandatory: boolean;
}

/** Required documents (agenda documental) for a branch + claim cause pair. */
@Injectable({ providedIn: 'root' })
export class DocumentRulesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/document-requirements`;

  get(branchId: number, claimCauseId: number): Observable<string[]> {
    return this.http.get<string[]>(this.base, {
      params: { branchId: String(branchId), claimCauseId: String(claimCauseId) },
    });
  }

  save(
    branchId: number,
    claimCauseId: number,
    documentTypes: string[],
  ): Observable<DocumentRequirementDto[]> {
    return this.http.put<DocumentRequirementDto[]>(this.base, documentTypes, {
      params: { branchId: String(branchId), claimCauseId: String(claimCauseId) },
    });
  }
}
