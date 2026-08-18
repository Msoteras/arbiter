import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { Policy } from '../../core/models/policy';

/**
 * Pólizas del asegurado (GET /api/v1/policies en cases-service). Alimenta el
 * autocompletado del alta de denuncia: el asegurado elige una de SUS pólizas —
 * de cualquier aseguradora — en vez de tipear el número a ciegas.
 */
@Injectable({ providedIn: 'root' })
export class PolicyService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/policies`;
  private readonly claimCausesUrl = `${environment.apiBaseUrl}/claim-causes`;

  /** Todas las pólizas del asegurado, de todas las aseguradoras (vista centralizada). */
  listByInsured(insuredId: string): Observable<Policy[]> {
    return this.http.get<Policy[]>(this.baseUrl, { params: { insuredId } });
  }

  getByNumber(policyNumber: string): Observable<Policy> {
    return this.http.get<Policy>(`${this.baseUrl}/${encodeURIComponent(policyNumber)}`);
  }

  /**
   * Hechos generadores (claim_cause) del ramo dado — pobla el selector "¿Qué te pasó?" del alta de
   * denuncia. Son distintos por ramo, así que el wizard los pide según la póliza elegida en vez de
   * ofrecer una lista fija (que fallaba con 422 al elegir un hecho inexistente en ese ramo).
   *
   * `policyNumber` además recorta los hechos que la cobertura de esa póliza excluye — sin esto, el
   * selector dejaba elegir p. ej. "Hurto" sobre una póliza cuya cobertura de Robo no lo cubre, y
   * recién se enteraba en la clasificación, ya subida la documentación.
   */
  listClaimCauses(branch: string, policyNumber?: string): Observable<string[]> {
    const params: Record<string, string> = { branch };
    if (policyNumber) {
      params['policyNumber'] = policyNumber;
    }
    return this.http.get<string[]>(this.claimCausesUrl, { params });
  }
}
