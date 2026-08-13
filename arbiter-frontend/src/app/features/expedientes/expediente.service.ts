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
  // Fecha/hora en que el asegurado hizo la denuncia policial, tal como la declara. Opcional: no
  // todo hecho generador lleva denuncia policial (el wizard solo pide el dato cuando la agenda
  // documental del ramo incluye `police_report`).
  //
  // Es la DECLARACIÓN, no lo que diga la constancia. Cuando exista extracción estructurada del
  // documento (H0007), esa fecha va en un dato aparte: si sobreescribiera a esta se pierde el
  // cruce, y la discrepancia entre lo declarado y lo que dice el papel es justamente la señal
  // que le daría contenido al DocumentInconsistencyEvaluator (D4b).
  policeReportAt?: string;
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
  /**
   * Filtro "Analista" del referente: expedientes de un analista puntual. A diferencia de
   * `assignedToMe`, acá el id sí viaja — sale de la lista de `analystWorkload()`, que ya es de su
   * propia aseguradora.
   */
  analystId?: number;
  /** Lente "Sin asignar": expedientes sin analista todavía. Excluyente con las otras lentes. */
  unassigned?: boolean;
  /** Lente "Asignados" (referente): expedientes con analista, sin importar quién. Excluyente. */
  assigned?: boolean;
  /** Lente "Alerta de fraude": expedientes con riesgo alto o crítico. Excluyente con las otras. */
  fraudAlert?: boolean;
}

/**
 * Carga de trabajo de un analista del equipo — espejo de AnalystWorkloadResponse del cases-service.
 * `activeCases` cuenta solo expedientes activos (no resueltos). La usa el inicio del referente.
 */
export interface AnalystWorkload {
  analystId: number;
  name: string;
  activeCases: number;
}

/** Conteos de las lentes de la bandeja — espejo de LensSummaryResponse del cases-service. */
export interface LensSummary {
  all: number;
  mine: number;
  assigned: number;
  unassigned: number;
  fraud: number;
}

/**
 * Resumen de los expedientes asignados al analista logueado — espejo de AssignedCaseSummaryResponse.
 * `byStatus` mapea nombre de CaseStatus → cantidad (solo estados con al menos uno). La usa el inicio
 * del analista para las tarjetas (pendientes / en trámite / resueltos / riesgo alto).
 */
export interface AssignedCaseSummary {
  total: number;
  byStatus: Record<string, number>;
  highRisk: number;
}

@Injectable({ providedIn: 'root' })
export class ExpedienteService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/cases`;

  /** Nombres distintos de hechos generadores (todos los ramos), para el filtro de la bandeja. */
  claimCauseNames(): Observable<string[]> {
    return this.http.get<string[]>(`${environment.apiBaseUrl}/claim-causes/all`);
  }

  /**
   * `aseguradora` sólo lo manda el portal del asegurado, y sólo hace falta si es cliente de más de
   * una compañía: los números de expediente se repiten entre aseguradoras, así que sin esto el
   * back siempre resolvía contra la del login y los de la otra quedaban inalcanzables. El back lo
   * valida contra el token, no confía en el parámetro.
   */
  getById(id: string | number, aseguradora?: string | null): Observable<ExpedienteResponse> {
    const options = aseguradora ? { params: new HttpParams().set('aseguradora', aseguradora) } : {};
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
    if (params.analystId != null) query['analystId'] = String(params.analystId);
    if (params.assignedToMe) query['assignedToMe'] = 'true';
    if (params.unassigned) query['unassigned'] = 'true';
    if (params.assigned) query['assigned'] = 'true';
    if (params.fraudAlert) query['fraudAlert'] = 'true';
    return this.http.get<PagedResponse<ExpedienteResponse>>(this.baseUrl, { params: query });
  }

  create(
    request: CaseCreateRequest,
    documents?: Map<string, File>,
  ): Observable<ExpedienteResponse> {
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

  recordAnalystDecision(
    caseId: number,
    request: AnalystDecisionRequest,
  ): Observable<{ status: string }> {
    return this.http.post<{ status: string }>(`${this.baseUrl}/${caseId}/decision`, request);
  }

  /**
   * Reintenta la clasificación de un expediente que quedó en CLASSIFICATION_FAILED. Lo devuelve a
   * PENDING_CLASSIFICATION y re-dispara el análisis en el backend. Solo válido desde el estado
   * fallido (otro estado → 409).
   */
  retryClassification(caseId: number): Observable<ExpedienteResponse> {
    return this.http.post<ExpedienteResponse>(`${this.baseUrl}/${caseId}/retry-classification`, {});
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

  /**
   * Carga de trabajo del equipo: cada analista de la aseguradora con su cantidad de expedientes
   * activos asignados (incluye a los que tienen cero). Solo para el referente. Alimenta el panel
   * "Carga del equipo" del inicio.
   */
  /** Los 5 conteos de las lentes en un request, sobre los filtros vigentes (sin paginado ni orden). */
  lensSummary(params: ExpedienteListParams = {}): Observable<LensSummary> {
    const query: Record<string, string> = {};
    if (params.status) query['status'] = params.status;
    if (params.claimCause) query['claimCause'] = params.claimCause;
    if (params.policyNumber) query['policyNumber'] = params.policyNumber;
    if (params.insuredId) query['insuredId'] = params.insuredId;
    if (params.eventDateFrom) query['eventDateFrom'] = params.eventDateFrom;
    if (params.eventDateTo) query['eventDateTo'] = params.eventDateTo;
    if (params.q) query['q'] = params.q;
    if (params.riskBand) query['riskBand'] = params.riskBand;
    if (params.analystId != null) query['analystId'] = String(params.analystId);
    return this.http.get<LensSummary>(`${this.baseUrl}/lens-summary`, { params: query });
  }

  analystWorkload(): Observable<AnalystWorkload[]> {
    return this.http.get<AnalystWorkload[]>(`${this.baseUrl}/analysts/workload`);
  }

  /**
   * Resumen de los expedientes asignados al analista logueado (conteo por estado + riesgo alto).
   * El backend resuelve "yo" contra el token. Alimenta las tarjetas del inicio del analista.
   */
  assignedSummary(): Observable<AssignedCaseSummary> {
    return this.http.get<AssignedCaseSummary>(`${this.baseUrl}/assigned/summary`);
  }
}
