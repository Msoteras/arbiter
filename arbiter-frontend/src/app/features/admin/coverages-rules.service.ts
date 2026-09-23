import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/** How the coverage's payable ceiling is computed. */
export type SettlementBasis = 'SUM_INSURED' | 'LESSER_OF_SUM_AND_REPLACEMENT';

export type SettlementFormula = 'TOTAL_LOSS' | 'REPAIR';

/** Mirrors cases-service CoverageDetailResponse. */
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
  settlementFormula: SettlementFormula;
  settlementBasis: SettlementBasis;
  /** 0..1 fraction like `deductibleRatio` (0.5 = 50%). `null` = the 2nd event isn't reduced. */
  secondEventRatio: number | null;
  deductPendingInstallments: boolean;
  deductOverdueBalance: boolean;
  exclusions: string[] | null;
}

/** One count per branch that has at least one coverage. */
export interface CoverageSummary {
  branchId: number;
  coverageCount: number;
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
  settlementFormula: SettlementFormula;
  settlementBasis: SettlementBasis;
  secondEventRatio: number | null;
  deductPendingInstallments: boolean;
  deductOverdueBalance: boolean;
  exclusions: string[];
}

/**
 * Coverages CRUD against cases-service, which owns the table. There is deliberately no sum insured
 * here: it lives on the policy, not on the coverage.
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

  /** Coverage counts for every branch in one call, so the branch list doesn't fetch each one. */
  summary(): Observable<CoverageSummary[]> {
    return this.http.get<CoverageSummary[]>(`${this.base}/summary`);
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
