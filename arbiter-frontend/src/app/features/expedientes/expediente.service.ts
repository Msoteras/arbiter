import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ExpedienteResponse } from '../../core/models/expediente';

export interface CaseCreateRequest {
  branch: string;
  product: string;
  claimCause: string;
  insuredItem: string;
  insuredId: string;
  policyNumber: string;
  description: string;
  eventDate: string;
  eventLocation: string;
  claimedAmount?: number;
}

// El backend solo acepta APPROVE/APROBAR o REJECT/RECHAZAR (human-in-the-loop:
// el analista aprueba o rechaza; no hay otras salidas).
export interface AnalystDecisionRequest {
  analystId: string;
  decision: 'APPROVE' | 'REJECT';
}

@Injectable({ providedIn: 'root' })
export class ExpedienteService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/cases`;

  getById(id: string | number): Observable<ExpedienteResponse> {
    return this.http.get<ExpedienteResponse>(`${this.baseUrl}/${id}`);
  }

  /**
   * Lista expedientes, más recientes primero. Filtros opcionales y combinables:
   * `status` (bandeja del analista) e `insuredId` (expedientes del asegurado —
   * hasta que se integre Auth0, se pasa explícito; después saldrá del JWT).
   */
  list(status?: string, insuredId?: string): Observable<ExpedienteResponse[]> {
    const params: Record<string, string> = {};
    if (status) params['status'] = status;
    if (insuredId) params['insuredId'] = insuredId;
    return this.http.get<ExpedienteResponse[]>(this.baseUrl, { params });
  }

  create(request: CaseCreateRequest, documents?: Map<string, File>): Observable<ExpedienteResponse> {
    const formData = new FormData();
    formData.append('case', new Blob([JSON.stringify(request)], { type: 'application/json' }));
    if (documents) {
      documents.forEach((file, type) => formData.append(type, file));
    }
    return this.http.post<ExpedienteResponse>(this.baseUrl, formData);
  }

  uploadDocuments(caseId: number, documents: Map<string, File>): Observable<ExpedienteResponse> {
    const formData = new FormData();
    documents.forEach((file, type) => formData.append(type, file));
    return this.http.post<ExpedienteResponse>(`${this.baseUrl}/${caseId}/documents`, formData);
  }

  recordAnalystDecision(caseId: number, request: AnalystDecisionRequest): Observable<{ status: string }> {
    return this.http.post<{ status: string }>(`${this.baseUrl}/${caseId}/decision`, request);
  }
}
