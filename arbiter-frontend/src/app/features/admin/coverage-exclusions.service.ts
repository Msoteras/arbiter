import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { environment } from '../../../environments/environment';

export interface ClaimCauseOption {
  id: number;
  name: string;
}

/** Shape of the COVERAGE_EXCLUSION rule's configuration JSONB. */
interface CoverageExclusionConfig {
  excludedClaimCauseIds: number[] | null;
}

/**
 * Hard coverage exclusions: which claim causes each coverage does NOT cover. Unlike the free-text
 * ones (BusinessRulesTextService), the rules engine evaluates these and audits them in rule_result.
 * Stored per coverage, not per branch.
 */
@Injectable({ providedIn: 'root' })
export class CoverageExclusionsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules`;

  listClaimCauses(branchId: number): Observable<ClaimCauseOption[]> {
    return this.http.get<ClaimCauseOption[]>(`${this.base}/claim-causes`, {
      params: { branchId: String(branchId) },
    });
  }

  get(coverageId: number): Observable<number[]> {
    return this.http
      .get<CoverageExclusionConfig>(`${this.base}/coverage-exclusions`, {
        params: { coverageId: String(coverageId) },
      })
      .pipe(map((config) => config.excludedClaimCauseIds ?? []));
  }

  save(branchId: number, coverageId: number, excludedClaimCauseIds: number[]): Observable<unknown> {
    return this.http.put<unknown>(
      `${this.base}/coverage-exclusions`,
      { excludedClaimCauseIds },
      { params: { branchId: String(branchId), coverageId: String(coverageId) } },
    );
  }
}
