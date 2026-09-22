import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/** Cobertura real del catálogo (cases-service GET /coverages?branchId). */
export interface CoverageOption {
  id: number;
  name: string;
}

/**
 * Umbrales Fast Track tal como los persiste rules-service (JSONB en insurer_rule.configuration).
 * Calca el FastTrackConfigDto del back campo por campo — es lo que el motor lee para el gate
 * determinístico. Un campo null significa "ese criterio no aplica".
 */
export interface FastTrackConfigDto {
  maxClaimedAmountRatio: number | null;
  maxPriorClaims: number | null;
  /** Ventana para contar `maxPriorClaims`. null = histórico completo del asegurado. */
  priorClaimsWindowMonths: number | null;
  /** Antigüedad mínima de la póliza al momento del hecho. null = no se exige. */
  minPolicyAgeMonths: number | null;
  requiresUpToDatePolicy: boolean | null;
  requiredDocumentTypes: string[];
  /**
   * Los mismos criterios en castellano. No deciden nada —el gate son los umbrales— pero viajan al
   * prompt del modelo como descripción de la política de Fast Track. Antes salían hardcodeados del
   * backend y podían contradecir a los números configurados acá (D14).
   */
  criteria: string[];
}

/** Confirmación de guardado: la fila de insurer_rule (FAST_TRACK) tal como quedó en la DB. */
export interface FastTrackRuleResponse {
  id: number;
  branchId: number;
  coverageId: number;
  config: FastTrackConfigDto;
}

/**
 * The referente's Fast Track, persisted for real (rules-service :8081 + cases-service :8083, routed
 * by the proxy). The FAST_TRACK rule lives PER COVERAGE in insurer_rule — each coverage demands
 * different documents (a theft doesn't come with a repair quote) — and the classification engine
 * reads it that way ({@code getByCoverage(coverageId)}). So it's always read and saved one coverage
 * at a time, and there's deliberately no per-branch operation: fanning one config out to every
 * coverage of the branch is what overwrote Robo and Hurto with Daño accidental's documents.
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
