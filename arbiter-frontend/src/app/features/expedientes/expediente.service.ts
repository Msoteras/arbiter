import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ExpedienteResponse } from '../../core/models/expediente';
import { CaseDocument } from '../../core/models/case-document';

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
  pep: boolean;
  imageConsent: boolean;
  contactEmail?: string;
  contactPhone?: string;
}

// El backend solo acepta APPROVE/APROBAR o REJECT/RECHAZAR (human-in-the-loop:
// el analista aprueba o rechaza; no hay otras salidas). Sin analystId: cases-service
// lo resuelve del JWT del que llama, no confía en lo que mande el cliente.
export interface AnalystDecisionRequest {
  decision: 'APPROVE' | 'REJECT';
  justification: string;
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
  /** Nivel de alerta de fraude, match exacto. */
  riskBand?: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  /**
   * Lente "Míos" de la bandeja: solo los expedientes asignados al analista logueado. Omitirlo
   * (o `false`) es la lente "Todos".
   *
   * Es un flag y no un id porque el id de analista es local al esquema de cada aseguradora:
   * quién es "yo" lo resuelve el backend contra el token. Es "de quién es el expediente", no
   * "qué puede ver este usuario" — eso último ya lo acota el tenant.
   */
  assignedToMe?: boolean;
}

@Injectable({ providedIn: 'root' })
export class ExpedienteService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/cases`;

  /**
   * `aseguradora` sólo lo manda el portal del asegurado, y sólo hace falta si es cliente de más de
   * una compañía: los números de expediente se repiten entre aseguradoras, así que sin esto el
   * back siempre resolvía contra la del login y los de la otra quedaban inalcanzables. El back lo
   * valida contra el token, no confía en el parámetro.
   */
  getById(id: string | number, aseguradora?: string | null): Observable<ExpedienteResponse> {
    const options = aseguradora
      ? { params: new HttpParams().set('aseguradora', aseguradora) }
      : {};
    return this.http.get<ExpedienteResponse>(`${this.baseUrl}/${id}`, options);
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
    if (params.riskBand) query['riskBand'] = params.riskBand;
    if (params.assignedToMe) query['assignedToMe'] = 'true';
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

  /** Metadata de los adjuntos del expediente (sin el contenido). */
  listDocuments(caseId: number): Observable<CaseDocument[]> {
    return this.http.get<CaseDocument[]>(`${this.baseUrl}/${caseId}/documents`);
  }

  /**
   * Contenido del adjunto. Va por HttpClient (y no por un <a href>) porque el endpoint
   * exige el JWT: el authInterceptor solo alcanza a las requests del HttpClient, una
   * navegación del browser saldría sin header y volvería 401.
   */
  downloadDocument(caseId: number, documentId: number): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/${caseId}/documents/${documentId}`, {
      responseType: 'blob',
    });
  }

  recordAnalystDecision(caseId: number, request: AnalystDecisionRequest): Observable<{ status: string }> {
    return this.http.post<{ status: string }>(`${this.baseUrl}/${caseId}/decision`, request);
  }

  /**
   * Pone a `analystId` como dueño del expediente. Un solo analista por expediente: reasignar
   * reemplaza al anterior. Asignar NO resuelve — el expediente sigue necesitando la decisión
   * explícita del analista (`recordAnalystDecision`).
   *
   * `analystId` es el id que devuelve `GET /auth/users/analysts`, local a la aseguradora.
   */
  assign(caseId: number, analystId: number): Observable<ExpedienteResponse> {
    return this.http.post<ExpedienteResponse>(`${this.baseUrl}/${caseId}/assign`, { analystId });
  }

  /** Libera el expediente: queda sin dueño y disponible para que lo tome otro analista. */
  unassign(caseId: number): Observable<ExpedienteResponse> {
    return this.http.delete<ExpedienteResponse>(`${this.baseUrl}/${caseId}/assign`);
  }
}
