import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, of, switchMap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';

export interface CoverageOption {
  id: number;
  name: string;
}

/**
 * Mirrors rules-service FastTrackConfigDto: the thresholds of the deterministic Fast Track gate.
 * A null field means that criterion doesn't apply.
 */
export interface FastTrackConfigDto {
  maxClaimedAmountRatio: number | null;
  maxPriorClaims: number | null;
  /** Window for counting `maxPriorClaims`. null = the insured's whole history. */
  priorClaimsWindowMonths: number | null;
  /** Minimum policy age at the date of loss. null = not required. */
  minPolicyAgeMonths: number | null;
  requiresUpToDatePolicy: boolean | null;
  requiredDocumentTypes: string[];
  /**
   * The same criteria as prose. They decide nothing (the thresholds are the gate), but they reach the
   * LLM prompt as the description of the Fast Track policy.
   */
  criteria: string[];
}

export interface FastTrackRuleResponse {
  id: number;
  branchId: number;
  coverageId: number;
  config: FastTrackConfigDto;
}

/**
 * Rules are keyed by coverage, but this screen configures Fast Track per branch, so saving fans out
 * the same config to every coverage of the branch (the classification engine reads it per coverage).
 */
@Injectable({ providedIn: 'root' })
export class FastTrackRulesService {
  private readonly http = inject(HttpClient);
  private readonly rulesBase = `${environment.apiBaseUrl}/rules`;
  private readonly coveragesBase = `${environment.apiBaseUrl}/coverages`;

  listCoverages(branchId: number): Observable<CoverageOption[]> {
    return this.http.get<CoverageOption[]>(this.coveragesBase, {
      params: { branchId: String(branchId) },
    });
  }

  getFastTrack(branchId: number, coverageId: number): Observable<FastTrackConfigDto> {
    return this.http.get<FastTrackConfigDto>(`${this.rulesBase}/fast-track`, {
      params: { branchId: String(branchId), coverageId: String(coverageId) },
    });
  }

  saveFastTrack(
    branchId: number,
    coverageId: number,
    config: FastTrackConfigDto,
  ): Observable<FastTrackRuleResponse> {
    return this.http.put<FastTrackRuleResponse>(`${this.rulesBase}/fast-track`, config, {
      params: { branchId: String(branchId), coverageId: String(coverageId) },
    });
  }

  /** Reads the first coverage: the fan-out on save keeps them all in sync. */
  loadForBranch(branchId: number): Observable<FastTrackConfigDto | null> {
    return this.listCoverages(branchId).pipe(
      switchMap((covs) => (covs.length ? this.getFastTrack(branchId, covs[0].id) : of(null))),
    );
  }

  saveForBranch(branchId: number, config: FastTrackConfigDto): Observable<FastTrackRuleResponse[]> {
    return this.listCoverages(branchId).pipe(
      switchMap((covs) => {
        if (!covs.length) {
          return throwError(
            () => new Error('El ramo no tiene coberturas cargadas en el catálogo.'),
          );
        }
        return forkJoin(covs.map((c) => this.saveFastTrack(branchId, c.id, config)));
      }),
    );
  }
}
