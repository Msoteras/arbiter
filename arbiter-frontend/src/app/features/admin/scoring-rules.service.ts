import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { FactorWeight, RiskBandCut } from '../../core/models/business-rules';

/** Scoring de la aseguradora tal como lo persiste rules-service — calca ScoringConfigDto campo por campo. */
export interface ScoringConfigDto {
  enabled: boolean;
  fullAnalysisOnFastTrack: boolean;
  factors: FactorWeight[];
  bands: RiskBandCut[];
}

/** Confirmación de guardado: la fila de scoring_configuration + su config, tal como quedó en la DB. */
export interface ScoringConfigResponse {
  id: number;
  config: ScoringConfigDto;
}

/**
 * Scoring de fraude (solapa Scoring) contra rules-service. Una sola config por aseguradora, no
 * por ramo — todos los ramos comparten el mismo scoring.
 */
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
