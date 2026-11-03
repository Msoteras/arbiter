import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { RiskBand } from '../../core/models/risk-band';

/** Mirrors common-lib's DocumentRequirementItem. */
export interface DocumentRequirementItem {
  documentType: string;
  /** Null: every case needs it. Otherwise, only cases from that band up. */
  minRiskBand: RiskBand | null;
}

export interface DocumentRequirementDto {
  id: number;
  documentType: string;
  claimCauseId: number;
  minRiskBand: RiskBand | null;
}

/** Required documents (agenda documental) for a branch + claim cause pair. */
@Injectable({ providedIn: 'root' })
export class DocumentRulesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/document-requirements`;

  get(branchId: number, claimCauseId: number): Observable<DocumentRequirementItem[]> {
    return this.http.get<DocumentRequirementItem[]>(this.base, {
      params: { branchId: String(branchId), claimCauseId: String(claimCauseId) },
    });
  }

  save(
    branchId: number,
    claimCauseId: number,
    documents: DocumentRequirementItem[],
  ): Observable<DocumentRequirementDto[]> {
    return this.http.put<DocumentRequirementDto[]>(this.base, documents, {
      params: { branchId: String(branchId), claimCauseId: String(claimCauseId) },
    });
  }
}
