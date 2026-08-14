import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { environment } from '../../../environments/environment';

/** Hecho generador (id + nombre) para el selector de inclusiones. */
export interface ClaimCauseOption {
  id: number;
  name: string;
}

/** Forma del configuration JSONB de la regla COVERAGE_INCLUSION. */
interface CoverageInclusionConfig {
  includedClaimCauseIds: number[] | null;
}

/**
 * Hechos generadores QUE SÍ cubre cada cobertura, contra rules-service: a diferencia de las
 * exclusiones en texto (BusinessRulesTextService), esta la evalúa el motor por código
 * (CoverageRuleEvaluator) y la audita en rule_result. Se guarda por cobertura, no por ramo — la
 * cobertura de qué se cubre es por cobertura por definición.
 *
 * <p>Antes esto era una lista NEGRA (coverage-exclusions): sin config, una cobertura cubría todo
 * por default. Se pasó a lista BLANCA — sin config, una cobertura no cubre nada — porque el default
 * permisivo dejaba pasar sin avisar denuncias de hechos generadores que la cobertura nunca tuvo que
 * cubrir (ej. una caída sobre una cobertura de robo).
 */
@Injectable({ providedIn: 'root' })
export class CoverageInclusionsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules`;

  /** Catálogo de hechos generadores del ramo (id + nombre). */
  listClaimCauses(branchId: number): Observable<ClaimCauseOption[]> {
    return this.http.get<ClaimCauseOption[]>(`${this.base}/claim-causes`, {
      params: { branchId: String(branchId) },
    });
  }

  /** Ids de los hechos generadores que la cobertura cubre hoy. */
  get(coverageId: number): Observable<number[]> {
    return this.http
      .get<CoverageInclusionConfig>(`${this.base}/coverage-inclusions`, {
        params: { coverageId: String(coverageId) },
      })
      .pipe(map((config) => config.includedClaimCauseIds ?? []));
  }

  save(branchId: number, coverageId: number, includedClaimCauseIds: number[]): Observable<unknown> {
    return this.http.put<unknown>(
      `${this.base}/coverage-inclusions`,
      { includedClaimCauseIds },
      { params: { branchId: String(branchId), coverageId: String(coverageId) } },
    );
  }
}
