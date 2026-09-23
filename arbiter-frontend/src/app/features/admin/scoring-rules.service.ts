import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
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

  get(): Observable<ScoringConfigDto> {
    return this.http.get<ScoringConfigDto>(this.base);
  }

  save(config: ScoringConfigDto): Observable<ScoringConfigResponse> {
    return this.http.put<ScoringConfigResponse>(this.base, config);
  }
}
