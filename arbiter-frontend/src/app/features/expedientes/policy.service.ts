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

  /** Todas las pólizas del asegurado, de todas las aseguradoras (vista centralizada). */
  listByInsured(insuredId: string): Observable<Policy[]> {
    return this.http.get<Policy[]>(this.baseUrl, { params: { insuredId } });
  }

  getByNumber(policyNumber: string): Observable<Policy> {
    return this.http.get<Policy>(`${this.baseUrl}/${encodeURIComponent(policyNumber)}`);
  }
}
