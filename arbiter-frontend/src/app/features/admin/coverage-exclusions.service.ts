import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { environment } from '../../../environments/environment';

/** Hecho generador (id + nombre) para el selector de exclusiones. */
export interface ClaimCauseOption {
  id: number;
  name: string;
}

/** Forma del configuration JSONB de la regla COVERAGE_EXCLUSION. */
interface CoverageExclusionConfig {
  excludedClaimCauseIds: number[] | null;
}

/**
 * Exclusiones DURAS de cobertura contra rules-service: qué hechos generadores NO cubre cada
 * cobertura. A diferencia de las exclusiones en texto (BusinessRulesTextService), estas las evalúa
 * el motor por código (CoverageRuleEvaluator) y las audita en rule_result. Se guardan por cobertura,
 * no por ramo — una exclusión es por cobertura por definición.
 */
@Injectable({ providedIn: 'root' })
export class CoverageExclusionsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules`;

  /** Catálogo de hechos generadores del ramo (id + nombre). */
  listClaimCauses(branchId: number): Observable<ClaimCauseOption[]> {
    return this.http.get<ClaimCauseOption[]>(`${this.base}/claim-causes`, {
      params: { branchId: String(branchId) },
    });
  }

  /** Ids de los hechos generadores que la cobertura excluye hoy. */
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
