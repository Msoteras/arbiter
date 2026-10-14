import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { QueryCache } from '../../core/cache/query-cache';
import { Policy } from '../../core/models/policy';

@Injectable({ providedIn: 'root' })
export class PolicyService {
  private readonly http = inject(HttpClient);
  private readonly cache = inject(QueryCache);
  private readonly baseUrl = `${environment.apiBaseUrl}/policies`;
  private readonly claimCausesUrl = `${environment.apiBaseUrl}/claim-causes`;

  /**
   * Policies across all insurers. Expired ones are excluded by default because the claim wizard
   * uses this list and an expired policy would only be rejected at the end; the profile asks for them.
   */
  listByInsured(insuredId: string, includeExpired = false): Observable<Policy[]> {
    return this.cache.read(this.policiesKey(insuredId, includeExpired), (context) =>
      this.fetchPolicies(insuredId, includeExpired, context),
    );
  }

  /** Both lists: the portal's "Mis pólizas" and the first step of a new claim. */
  prefetch(insuredId: string): void {
    for (const includeExpired of [true, false]) {
      this.cache.prefetch(this.policiesKey(insuredId, includeExpired), (context) =>
        this.fetchPolicies(insuredId, includeExpired, context),
      );
    }
  }

  private policiesKey(insuredId: string, includeExpired: boolean): string {
    return `insured:policies:${insuredId}:${includeExpired}`;
  }

  private fetchPolicies(insuredId: string, includeExpired: boolean, context: HttpContext) {
    return this.http.get<Policy[]>(this.baseUrl, {
      params: { insuredId, ...(includeExpired ? { includeExpired: true } : {}) },
      context,
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
