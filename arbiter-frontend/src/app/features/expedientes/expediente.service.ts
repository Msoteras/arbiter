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

// Forma de Page<T> de Spring Data — así responde GET /api/v1/cases desde que el backend
// pagina (historia "Búsqueda y filtrado de expedientes"). Solo los campos que usamos hoy;
// Spring manda más metadata (pageable, sort, empty, etc.) que ignoramos.
export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// Todos los filtros que GET /api/v1/cases acepta hoy (opcionales y combinables) + paginación/orden.
export interface ExpedienteListParams {
  status?: string;
  claimCause?: string;
  policyNumber?: string;
  insuredId?: string;
  /** ISO yyyy-MM-dd. Filtra por fecha del hecho (eventDate), no por fecha de denuncia. */
  eventDateFrom?: string;
  eventDateTo?: string;
  page?: number;
  size?: number;
  /** Formato Spring Data, ej. "eventDate,desc". Default del backend: "id,desc". */
  sort?: string;
  /**
   * Búsqueda de texto libre (case-insensitive, substring) por número de expediente, póliza o
   * asegurado (insuredId/insuredName — este último nullable hasta la primera clasificación).
   * Se combina por AND con el resto de los filtros.
   */
  q?: string;
}

@Injectable({ providedIn: 'root' })
export class ExpedienteService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/cases`;

  getById(id: string | number): Observable<ExpedienteResponse> {
    return this.http.get<ExpedienteResponse>(`${this.baseUrl}/${id}`);
  }

  /**
   * Lista expedientes paginados, más recientes primero por defecto. Todos los filtros de
   * `ExpedienteListParams` son opcionales y combinables — reflejan 1:1 lo que acepta
   * `GET /api/v1/cases` (historia "Búsqueda y filtrado de expedientes", backend). `insuredId` es
   * explícito hasta que se integre Auth0; después saldrá del JWT.
   */
  list(params: ExpedienteListParams = {}): Observable<PagedResponse<ExpedienteResponse>> {
    const query: Record<string, string> = {};
    if (params.status) query['status'] = params.status;
    if (params.claimCause) query['claimCause'] = params.claimCause;
    if (params.policyNumber) query['policyNumber'] = params.policyNumber;
    if (params.insuredId) query['insuredId'] = params.insuredId;
    if (params.eventDateFrom) query['eventDateFrom'] = params.eventDateFrom;
    if (params.eventDateTo) query['eventDateTo'] = params.eventDateTo;
    if (params.page != null) query['page'] = String(params.page);
    if (params.size != null) query['size'] = String(params.size);
    if (params.sort) query['sort'] = params.sort;
    if (params.q) query['q'] = params.q;
    return this.http.get<PagedResponse<ExpedienteResponse>>(this.baseUrl, { params: query });
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
