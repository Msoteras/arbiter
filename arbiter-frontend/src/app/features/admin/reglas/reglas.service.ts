import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';

export interface CatalogOption {
  id: number;
  name: string;
}

/** Umbrales Fast Track de una (rama, cobertura). Espejo de FastTrackConfigDto del backend. */
export interface FastTrackConfig {
  maxClaimedAmountRatio: number | null;
  maxPriorClaims: number | null;
  requiresUpToDatePolicy: boolean | null;
  requiredDocumentTypes: string[] | null;
}

/**
 * Backoffice de reglas del referente. El eje de una regla Fast Track es (ramo, cobertura), fiel
 * al DER: los ramos salen de rules-service (arbiter_common) y las coberturas de cases-service
 * (dueño de la tabla). Solo REFERENTE_ASEGURADORA — el RBAC lo aplica el backend.
 */
@Injectable({ providedIn: 'root' })
export class RulesService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiBaseUrl;

  branches(): Observable<CatalogOption[]> {
    return this.http.get<CatalogOption[]>(`${this.baseUrl}/rules/branches`);
  }

  coverages(branchId: number): Observable<CatalogOption[]> {
    return this.http.get<CatalogOption[]>(`${this.baseUrl}/coverages`, {
      params: { branchId: branchId.toString() },
    });
  }

  getFastTrack(branchId: number, coverageId: number): Observable<FastTrackConfig> {
    return this.http.get<FastTrackConfig>(`${this.baseUrl}/rules/fast-track`, {
      params: { branchId: branchId.toString(), coverageId: coverageId.toString() },
    });
  }

  saveFastTrack(branchId: number, coverageId: number, config: FastTrackConfig): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/rules/fast-track`, config, {
      params: { branchId: branchId.toString(), coverageId: coverageId.toString() },
    });
  }
}
