import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, of, switchMap, throwError } from 'rxjs';

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
  requiresUpToDatePolicy: boolean | null;
  requiredDocumentTypes: string[];
}

/** Confirmación de guardado: la fila de insurer_rule (FAST_TRACK) tal como quedó en la DB. */
export interface FastTrackRuleResponse {
  id: number;
  branchId: number;
  coverageId: number;
  config: FastTrackConfigDto;
}

/**
 * Persistencia real del Fast Track del referente contra el backend (rules-service :8081 +
 * cases-service :8083, ruteados por el proxy). El eje de reglas del DER es la COBERTURA, pero la
 * pantalla configura Fast Track por RAMO; por eso al guardar hacemos fan-out: escribimos la misma
 * config a todas las coberturas del ramo, que es como el motor de clasificación la lee
 * ({@code getByCoverage(coverageId)}). Separado del RulesConfigService (mock) a propósito: esta es
 * la primera solapa cableada de verdad; las demás siguen en el mock hasta tener su endpoint.
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

  saveFastTrack(branchId: number, coverageId: number, config: FastTrackConfigDto): Observable<FastTrackRuleResponse> {
    return this.http.put<FastTrackRuleResponse>(`${this.rulesBase}/fast-track`, config, {
      params: { branchId: String(branchId), coverageId: String(coverageId) },
    });
  }

  /** Carga la config del ramo desde la primera cobertura (todas quedan en sync por el fan-out). */
  loadForBranch(branchId: number): Observable<FastTrackConfigDto | null> {
    return this.listCoverages(branchId).pipe(
      switchMap((covs) => (covs.length ? this.getFastTrack(branchId, covs[0].id) : of(null))),
    );
  }

  /** Guarda la config a TODAS las coberturas del ramo (el motor lee por cobertura); una confirmación por cobertura. */
  saveForBranch(branchId: number, config: FastTrackConfigDto): Observable<FastTrackRuleResponse[]> {
    return this.listCoverages(branchId).pipe(
      switchMap((covs) => {
        if (!covs.length) {
          return throwError(() => new Error('El ramo no tiene coberturas cargadas en el catálogo.'));
        }
        return forkJoin(covs.map((c) => this.saveFastTrack(branchId, c.id, config)));
      }),
    );
  }
}
