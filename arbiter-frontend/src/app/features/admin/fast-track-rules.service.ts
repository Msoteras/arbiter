import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

export interface CoverageOption {
  id: number;
  name: string;
}

/** Mirrors rules-service FastTrackConfigDto; a null field means that criterion doesn't apply. */
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
 * The referente's Fast Track. The FAST_TRACK rule lives PER COVERAGE in insurer_rule (each coverage
 * demands different documents) and the engine reads it that way, so it's read and saved one coverage
 * at a time. Deliberately no per-branch operation: fanning one config out overwrote Robo and Hurto
 * with Daño accidental's documents.
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
}
