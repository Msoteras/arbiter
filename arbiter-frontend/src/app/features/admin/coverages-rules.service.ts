import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/** Cobertura tal como la persiste cases-service — calca CoverageDetailResponse campo por campo. */
export interface CoverageDetail {
  id: number;
  name: string;
  clause: string | null;
  deductibleRatio: number | null;
  reportingWindowDays: number | null;
  maxAnnualClaims: number | null;
  waitingPeriodDays: number | null;
  coversFamilyGroup: boolean;
  claimExhaustsCoverage: boolean;
  exclusions: string[] | null;
}

export interface CoverageUpsertRequest {
  name: string;
  clause: string | null;
  deductibleRatio: number | null;
  reportingWindowDays: number | null;
  maxAnnualClaims: number | null;
  waitingPeriodDays: number | null;
  coversFamilyGroup: boolean;
  claimExhaustsCoverage: boolean;
  exclusions: string[];
}

/**
 * CRUD real de coberturas (solapa Coberturas del referente) contra cases-service, dueño de la
 * tabla Coverage. `insuredAmount` no tiene equivalente acá a propósito: no existe tope de suma
 * asegurada por cobertura en el DER (docs/decisiones-reglas-a-validar.md, D4) — vive en la póliza.
 */
@Injectable({ providedIn: 'root' })
export class CoveragesRulesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/coverages`;

  listDetailed(branchId: number): Observable<CoverageDetail[]> {
    return this.http.get<CoverageDetail[]>(`${this.base}/detailed`, {
      params: { branchId: String(branchId) },
    });
  }

  create(branchId: number, request: CoverageUpsertRequest): Observable<CoverageDetail> {
    return this.http.post<CoverageDetail>(this.base, request, {
      params: { branchId: String(branchId) },
    });
  }

  update(coverageId: number, request: CoverageUpsertRequest): Observable<CoverageDetail> {
    return this.http.put<CoverageDetail>(`${this.base}/${coverageId}`, request);
  }

  remove(coverageId: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${coverageId}`);
  }
}
