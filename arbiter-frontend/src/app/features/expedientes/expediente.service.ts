import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ExpedienteResponse } from '../../core/models/expediente';
import { CaseDocument } from '../../core/models/case-document';
import { Policy } from '../../core/models/policy';
import {
  ExpertVerdict,
  OpcionesDerivacion,
  Peritaje,
  ProviderType,
  RepairOutcome,
} from '../../core/models/peritaje';
import {
  AntecedenteFraude,
  RegistrarAntecedenteRequest,
} from '../../core/models/antecedente-fraude';

export interface CaseCreateRequest {
  branch: string;
  product: string;
  claimCause: string;
  insuredItem: string;
  insuredId: string;
  policyNumber: string;
  description: string;
  eventDate: string;
  /** Street address only; locality and province have their own fields. */
  eventLocation: string;
  province?: string;
  locality?: string;
  // As DECLARED by the insured, not as read from the police report: the mismatch between the two
  // is itself a fraud signal, so the extracted date must never overwrite this one.
  policeReportAt?: string;
  claimedAmount?: number;
  contactEmail?: string;
  contactPhone?: string;
}

export interface EligibilityCheckRequest {
  insuredId: string;
  policyNumber: string;
  // Optional: the wizard checks once without it (arrears only) and again with it (term, waiting period).
  eventDate?: string;
  policeReportAt?: string;
}

export interface EligibilityCheckResponse {
  eligible: boolean;
  reason: string | null;
}

export interface IntakeDocumentsResponse {
  documentTypes: string[];
  /** `false` when no Fast Track list is configured and this is the full agenda. */
  fastTrackOnly: boolean;
}

// No analystId: cases-service takes it from the caller's JWT.
export interface AnalystDecisionRequest {
  decision: 'APPROVE' | 'REJECT';
  justification: string;
  /** Required when approving, forbidden when rejecting. */
  settlement?: SettlementDecisionRequest | null;
}

export interface SettlementDecisionRequest {
  replacementValue?: number | null;
  settledAmount: number;
  /** Required only when `settledAmount` differs from the backend's calculation. */
  adjustmentReason?: string | null;
}

export type SettlementBasis = 'SUM_INSURED' | 'LESSER_OF_SUM_AND_REPLACEMENT';

/** Built entirely by the backend (amount and wording) so text and math can't drift apart. */
export interface SettlementLine {
  kind: 'BASE' | 'DEDUCTION' | 'TOTAL';
  concept: string;
  detail: string | null;
  amount: number;
}

/** Either the authorized settlement or a proposal to confirm. */
export interface Settlement {
  formula: SettlementFormula;
  sumInsured: number;
  settlementBasis: SettlementBasis;
  replacementValue: number | null;
  deductibleRate: number | null;
  eventOrdinal: number;
  eventPercentage: number;
  pendingInstallments: number;
  installmentAmount: number | null;
  deductibleAmount: number;
  pendingInstallmentsAmount: number;
  overdueBalanceAmount: number;
  calculatedAmount: number;
  settledAmount: number | null;
  adjustmentReason: string | null;
  confirmed: boolean;
  confirmedAt: string | null;
  /** Null while it is only a proposal. */
  status: SettlementStatus | null;
  /** Branch authority limit: current one on a proposal, frozen one once saved. Null = no limit. */
  authorityLimit: number | null;
  returnReason: string | null;
  /** A suggestion only: not part of the calculation until the analyst takes it. */
  suggestedAmount: number | null;
  suggestedFrom: string | null;
  /**
   * `ACCREDITED_AMOUNT` feeds the calculation base; `SETTLED_AMOUNT` is the payout itself (the
   * expert's figure when the coverage settles by sum insured and has no accredited amount).
   */
  suggestedFor: 'ACCREDITED_AMOUNT' | 'SETTLED_AMOUNT' | null;
  breakdown: SettlementLine[];
  warnings: string[];
}

export type SettlementStatus = 'AUTHORIZED' | 'PENDING_AUTHORIZATION' | 'RETURNED';

export type SettlementFormula = 'TOTAL_LOSS' | 'REPAIR';

/** Subset of Spring Data's Page<T>. */
export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface ExpedienteListParams {
  status?: string | string[];
  claimCause?: string;
  policyNumber?: string;
  insuredId?: string;
  /** ISO yyyy-MM-dd, on the event date (not the filing date). */
  eventDateFrom?: string;
  eventDateTo?: string;
  page?: number;
  size?: number;
  /** Spring Data format, e.g. "eventDate,desc". Backend default: "id,desc". */
  sort?: string;
  /** Case-insensitive substring over case number, policy and insured; ANDed with the other filters. */
  q?: string;
  riskBand?: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  /** Open cases with no change in the last N days. */
  staleDays?: number;
  /** A flag, not an id: analyst ids are per-tenant, so the backend resolves "me" from the token. */
  assignedToMe?: boolean;
  /** Taken from `analystWorkload()`, which is already scoped to the supervisor's insurer. */
  analystId?: number;
  /** Mutually exclusive with the other lenses (as are `assigned` and `fraudAlert`). */
  unassigned?: boolean;
  assigned?: boolean;
  /** HIGH or CRITICAL risk. */
  fraudAlert?: boolean;
  followUp?:
    | 'EXPERT_REPORT_RECEIVED'
    | 'REPAIR_REPORT_RECEIVED'
    | 'RETURNED_BY_REFERENT'
    | 'AWAITING_REFERENT';
  /** Backend default: `ALL`. */
  scope?: 'OPEN' | 'CLOSED' | 'ALL';
  /** Only for an insured with policies at more than one insurer. */
  insurerId?: number;
}

export interface AnalystWorkload {
  analystId: number;
  name: string;
  activeCases: number;
}

export interface LensCounts {
  total: number;
  mine: number;
  assigned: number;
  unassigned: number;
  fraud: number;
}

export interface LensSummary {
  open: LensCounts;
  closed: LensCounts;
  all: LensCounts;
}

/** `byStatus` maps CaseStatus name to count, only for statuses with at least one case. */
export interface AssignedCaseSummary {
  total: number;
  byStatus: Record<string, number>;
  highRisk: number;
  /** PENDING_ANALYST_REVIEW cases awaiting the supervisor's sign-off. */
  awaitingReferent: number;
}

@Injectable({ providedIn: 'root' })
export class ExpedienteService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/cases`;

  claimCauseNames(): Observable<string[]> {
    return this.http.get<string[]>(`${environment.apiBaseUrl}/claim-causes/all`);
  }

  /**
   * `insurer` is sent only by the insured portal when the insured has several insurers: case ids
   * repeat across tenants. The backend validates it against the token.
   */
  getById(id: string | number, insurer?: string | null): Observable<ExpedienteResponse> {
    const options = insurer ? { params: new HttpParams().set('insurer', insurer) } : {};
    return this.http.get<ExpedienteResponse>(`${this.baseUrl}/${id}`, options);
  }

  list(params: ExpedienteListParams = {}): Observable<PagedResponse<ExpedienteResponse>> {
    const query: Record<string, string | string[]> = {};
    if (params.status?.length) query['status'] = params.status;
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
    if (params.followUp) query['followUp'] = params.followUp;
    if (params.scope) query['scope'] = params.scope;
    if (params.staleDays != null) query['staleDays'] = String(params.staleDays);
    if (params.insurerId != null) query['insurerId'] = String(params.insurerId);
    return this.http.get<PagedResponse<ExpedienteResponse>>(this.baseUrl, { params: query });
  }

  /** Same gate `create` runs (term, waiting period, arrears) without creating anything. */
  checkEligibility(request: EligibilityCheckRequest): Observable<EligibilityCheckResponse> {
    return this.http.post<EligibilityCheckResponse>(`${this.baseUrl}/eligibility`, request);
  }

  /**
   * The Fast Track list for the matching coverage, or the full agenda when none is configured; the rest
   * only if the claim misses Fast Track. 503 if the rules engine can't be read.
   */
  intakeDocuments(
    policyNumber: string,
    branch: string,
    claimCause: string,
  ): Observable<IntakeDocumentsResponse> {
    return this.http.get<IntakeDocumentsResponse>(`${this.baseUrl}/intake-documents`, {
      params: { policyNumber, branch, claimCause },
    });
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

  /** `insurer`: see `getById`; pass `ExpedienteResponse.insurerSlug`. */
  uploadDocuments(
    caseId: number,
    documents: Map<string, File>,
    insurer?: string | null,
  ): Observable<ExpedienteResponse> {
    const formData = new FormData();
    documents.forEach((file, type) => formData.append(type, file));
    const options = insurer ? { params: new HttpParams().set('insurer', insurer) } : {};
    return this.http.post<ExpedienteResponse>(
      `${this.baseUrl}/${caseId}/documents`,
      formData,
      options,
    );
  }

  listDocuments(caseId: number, insurer?: string | null): Observable<CaseDocument[]> {
    const options = insurer ? { params: new HttpParams().set('insurer', insurer) } : {};
    return this.http.get<CaseDocument[]>(`${this.baseUrl}/${caseId}/documents`, options);
  }

  /** Through HttpClient, not an <a href>: the endpoint needs the JWT the interceptor adds. */
  downloadDocument(caseId: number, documentId: number, insurer?: string | null): Observable<Blob> {
    const params = insurer ? new HttpParams().set('insurer', insurer) : undefined;
    return this.http.get(`${this.baseUrl}/${caseId}/documents/${documentId}`, {
      responseType: 'blob',
      params,
    });
  }

  recordAnalystDecision(
    caseId: number,
    request: AnalystDecisionRequest,
  ): Observable<{ status: string }> {
    return this.http.post<{ status: string }>(`${this.baseUrl}/${caseId}/decision`, request);
  }

  /** Read-only preview; `replacementValue` simulates accrediting that value. Saving is `recordAnalystDecision`. */
  settlement(caseId: number, replacementValue?: number | null): Observable<Settlement> {
    const params =
      replacementValue == null ? undefined : { replacementValue: String(replacementValue) };
    return this.http.get<Settlement>(`${this.baseUrl}/${caseId}/settlement`, { params });
  }

  /** Only valid from CLASSIFICATION_FAILED (409 otherwise). */
  retryClassification(caseId: number): Observable<ExpedienteResponse> {
    return this.http.post<ExpedienteResponse>(`${this.baseUrl}/${caseId}/retry-classification`, {});
  }

  /** Replaces any previous assignee. `analystId` comes from `GET /auth/users/analysts` (per tenant). */
  assign(caseId: number, analystId: number): Observable<ExpedienteResponse> {
    return this.http.post<ExpedienteResponse>(`${this.baseUrl}/${caseId}/assign`, { analystId });
  }

  /** Back to analyst review without reverting the previous decision. 409 from a non-terminal status. */
  reopen(caseId: number, reason: string): Observable<ExpedienteResponse> {
    return this.http.post<ExpedienteResponse>(`${this.baseUrl}/${caseId}/reopen`, { reason });
  }

  unassign(caseId: number): Observable<ExpedienteResponse> {
    return this.http.delete<ExpedienteResponse>(`${this.baseUrl}/${caseId}/assign`);
  }

  /** All lens counts in one request, over the current filters. */
  lensSummary(params: ExpedienteListParams = {}): Observable<LensSummary> {
    const query: Record<string, string | string[]> = {};
    if (params.status?.length) query['status'] = params.status;
    if (params.claimCause) query['claimCause'] = params.claimCause;
    if (params.policyNumber) query['policyNumber'] = params.policyNumber;
    if (params.insuredId) query['insuredId'] = params.insuredId;
    if (params.eventDateFrom) query['eventDateFrom'] = params.eventDateFrom;
    if (params.eventDateTo) query['eventDateTo'] = params.eventDateTo;
    if (params.q) query['q'] = params.q;
    if (params.riskBand) query['riskBand'] = params.riskBand;
    if (params.analystId != null) query['analystId'] = String(params.analystId);
    if (params.followUp) query['followUp'] = params.followUp;
    if (params.staleDays != null) query['staleDays'] = String(params.staleDays);
    return this.http.get<LensSummary>(`${this.baseUrl}/lens-summary`, { params: query });
  }

  /** Includes analysts with zero active cases. */
  analystWorkload(): Observable<AnalystWorkload[]> {
    return this.http.get<AnalystWorkload[]>(`${this.baseUrl}/analysts/workload`);
  }

  /** For display only; the backend enforces the same check when referring. */
  derivationOptions(
    caseId: number,
    providerType: ProviderType = 'ESTUDIO_LIQUIDADOR',
  ): Observable<OpcionesDerivacion> {
    return this.http.get<OpcionesDerivacion>(
      `${this.baseUrl}/${caseId}/expert-assessment/options`,
      {
        params: { providerType },
      },
    );
  }

  /** Newest first. */
  derivaciones(caseId: number): Observable<Peritaje[]> {
    return this.http.get<Peritaje[]>(`${this.baseUrl}/${caseId}/expert-assessment/all`);
  }

  derivarAPeritaje(
    caseId: number,
    expertFirmId: number,
    reason: string,
    providerType: ProviderType = 'ESTUDIO_LIQUIDADOR',
  ): Observable<Peritaje> {
    return this.http.post<Peritaje>(
      `${this.baseUrl}/${caseId}/expert-assessment`,
      { expertFirmId, reason },
      { params: { providerType } },
    );
  }

  cargarRespuestaServicioTecnico(
    caseId: number,
    outcome: RepairOutcome,
    note: string,
    repairCost: number | null,
    report: File,
  ): Observable<Peritaje> {
    const formData = new FormData();
    formData.append('report', report);
    formData.append('outcome', outcome);
    formData.append('note', note);
    // Required with QUOTE_SENT, rejected with IRREPARABLE.
    if (repairCost != null) {
      formData.append('repairCost', String(repairCost));
    }
    return this.http.post<Peritaje>(
      `${this.baseUrl}/${caseId}/expert-assessment/repair-report`,
      formData,
    );
  }

  /** Returns the case to review without reclassifying it. */
  cargarInformePericial(
    caseId: number,
    verdict: ExpertVerdict,
    note: string,
    indemnifiableAmount: number | null,
    report: File,
  ): Observable<Peritaje> {
    // In the body, not the query string: the note may contain personal data that would end up in
    // proxy logs. @RequestParam reads multipart fields too.
    const formData = new FormData();
    formData.append('report', report);
    formData.append('verdict', verdict);
    formData.append('note', note);
    // Empty is not zero: zero would mean the expert concluded nothing is owed.
    if (indemnifiableAmount != null) {
      formData.append('indemnifiableAmount', String(indemnifiableAmount));
    }
    return this.http.post<Peritaje>(`${this.baseUrl}/${caseId}/expert-assessment/report`, formData);
  }

  /** Current data, fetched lazily when the tab opens to spare an insurer-DB query on every detail load. */
  polizasDelAsegurado(caseId: number): Observable<Policy[]> {
    return this.http.get<Policy[]>(`${this.baseUrl}/${caseId}/insured-policies`);
  }

  /** Includes expired records: "had one that no longer counts" differs from "never had one". */
  antecedentesFraude(caseId: number): Observable<AntecedenteFraude[]> {
    return this.http.get<AntecedenteFraude[]>(`${this.baseUrl}/${caseId}/fraud-record/insured`);
  }

  /** `EXPERT_BACKED` requires a saved expert verdict confirming fraud (422 otherwise). */
  registrarAntecedente(
    caseId: number,
    request: RegistrarAntecedenteRequest,
  ): Observable<AntecedenteFraude> {
    return this.http.post<AntecedenteFraude>(`${this.baseUrl}/${caseId}/fraud-record`, request);
  }

  assignedSummary(): Observable<AssignedCaseSummary> {
    return this.http.get<AssignedCaseSummary>(`${this.baseUrl}/assigned/summary`);
  }
}
