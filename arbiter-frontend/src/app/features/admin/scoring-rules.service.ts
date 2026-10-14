import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CacheReadOptions, QueryCache } from '../../core/cache/query-cache';
import { FactorWeight, RiskBandCut } from '../../core/models/business-rules';

/** Mirrors rules-service ScoringConfigDto. */
export interface ScoringConfigDto {
  enabled: boolean;
  fullAnalysisOnFastTrack: boolean;
  factors: FactorWeight[];
  bands: RiskBandCut[];
}

export interface ScoringConfigResponse {
  id: number;
  config: ScoringConfigDto;
}

/** One scoring config per insurer, shared by every branch. */
@Injectable({ providedIn: 'root' })
export class ScoringRulesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/scoring`;

  private readonly cache = inject(QueryCache);
  private readonly fetch = (context: HttpContext) =>
    this.http.get<ScoringConfigDto>(this.base, { context });

  get(options?: CacheReadOptions): Observable<ScoringConfigDto> {
    return this.cache.read('rules:scoring', this.fetch, options);
  }

  prefetch(): void {
    this.cache.prefetch('rules:scoring', this.fetch);
  }

  save(config: ScoringConfigDto): Observable<ScoringConfigResponse> {
    return this.http.put<ScoringConfigResponse>(this.base, config);
  }
}
