import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CacheReadOptions, QueryCache } from '../../core/cache/query-cache';

/**
 * How much an analyst may authorize alone in a branch. A null `maxAmount` means no cap (any amount),
 * which is an answer, not missing data.
 */
export interface SettlementAuthority {
  branchId: number;
  branchName: string;
  maxAmount: number | null;
  updatedAt: string | null;
}

/** Mirrors PendingSettlementResponse. */
export interface PendingSettlement {
  caseId: number;
  insuredName: string | null;
  branch: string | null;
  claimCause: string | null;
  analystName: string | null;
  calculatedAmount: number;
  settledAmount: number;
  adjustmentReason: string | null;
  authorityLimit: number | null;
  /** Amount over the cap, already computed by the backend. */
  excess: number | null;
  confirmedAt: string;
  /** Days waiting. The claim's legal deadline keeps running meanwhile. */
  waitingFor: number;
}

/** Mirrors AuthorizedSettlementResponse. */
export interface AuthorizedSettlement {
  caseId: number;
  insuredName: string | null;
  branch: string | null;
  claimCause: string | null;
  analystName: string | null;
  calculatedAmount: number;
  settledAmount: number;
  adjustmentReason: string | null;
  authorityLimit: number | null;
  excess: number | null;
  confirmedAt: string;
  authorizedAt: string;
  /** null if that referente has no profile in the insurer's schema. */
  authorizedByName: string | null;
}

/** Per-branch settlement authority caps, and the queue of settlements that exceeded them. */
const PENDING_SETTLEMENTS_KEY = 'cases:settlements:pending';

@Injectable({ providedIn: 'root' })
export class SettlementAuthoritiesService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  private readonly cache = inject(QueryCache);
  private readonly fetchList = (context: HttpContext) =>
    this.http.get<SettlementAuthority[]>(`${this.base}/settlement-authorities`, { context });
  private readonly fetchAuthorized = (context: HttpContext) =>
    this.http.get<AuthorizedSettlement[]>(`${this.base}/cases/settlements/authorized`, {
      context,
    });
  private readonly fetchPending = (context: HttpContext) =>
    this.http.get<PendingSettlement[]>(`${this.base}/cases/settlements/pending-authorization`, {
      context,
    });

  /** Every branch, including uncapped ones (`maxAmount: null`). */
  list(options?: CacheReadOptions): Observable<SettlementAuthority[]> {
    return this.cache.read('rules:authorities', this.fetchList, options);
  }

  /** A null `maxAmount` removes the cap. */
  set(branchId: number, maxAmount: number | null): Observable<void> {
    return this.http.put<void>(`${this.base}/settlement-authorities/${branchId}`, { maxAmount });
  }

  /** Latest 50, most recent first. */
  authorized(): Observable<AuthorizedSettlement[]> {
    return this.cache.read('cases:settlements:authorized', this.fetchAuthorized);
  }

  /** Others change this list too: the screen holds a refresh until "Actualizar" (`hasNewer`). */
  pending(options?: CacheReadOptions): Observable<PendingSettlement[]> {
    return this.cache.read(PENDING_SETTLEMENTS_KEY, this.fetchPending, options);
  }

  hasNewerPending(): boolean {
    return this.cache.hasNewer(PENDING_SETTLEMENTS_KEY);
  }

  prefetch(): void {
    this.cache.prefetch('rules:authorities', this.fetchList);
    this.cache.prefetch('cases:settlements:authorized', this.fetchAuthorized);
    this.cache.prefetch(PENDING_SETTLEMENTS_KEY, this.fetchPending);
  }

  authorize(caseId: number): Observable<unknown> {
    return this.http.post(`${this.base}/cases/${caseId}/settlement/authorize`, {});
  }

  returnToAnalyst(caseId: number, reason: string): Observable<unknown> {
    return this.http.post(`${this.base}/cases/${caseId}/settlement/return`, { reason });
  }
}
