import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { Policy } from '../../core/models/policy';

@Injectable({ providedIn: 'root' })
export class PolicyService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/policies`;
  private readonly claimCausesUrl = `${environment.apiBaseUrl}/claim-causes`;

  /**
   * Policies across all insurers. Expired ones are excluded by default because the claim wizard
   * uses this list and an expired policy would only be rejected at the end; the profile asks for them.
   */
  listByInsured(insuredId: string, includeExpired = false): Observable<Policy[]> {
    return this.http.get<Policy[]>(this.baseUrl, {
      params: { insuredId, ...(includeExpired ? { includeExpired: true } : {}) },
    });
  }

  getByNumber(policyNumber: string): Observable<Policy> {
    return this.http.get<Policy>(`${this.baseUrl}/${encodeURIComponent(policyNumber)}`);
  }

  /** Claim causes differ per branch; `policyNumber` also drops the causes that policy's coverage excludes. */
  listClaimCauses(branch: string, policyNumber?: string): Observable<string[]> {
    const params: Record<string, string> = { branch };
    if (policyNumber) {
      params['policyNumber'] = policyNumber;
    }
    return this.http.get<string[]>(this.claimCausesUrl, { params });
  }
}
