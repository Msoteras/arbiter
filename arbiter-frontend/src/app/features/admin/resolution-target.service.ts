import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CacheReadOptions, QueryCache } from '../../core/cache/query-cache';

/** Backend bounds. A 0-day target could never be met. */
export const TARGET_DAYS_MIN = 1;
export const TARGET_DAYS_MAX = 365;

/**
 * The insurer's goal, in days, for closing a claim. A management target, not the legal deadline
 * (which is per case), and it may be stricter than the law.
 */
export interface ResolutionTarget {
  enabled: boolean;
  /** null until the insurer sets one. */
  targetDays: number | null;
}

@Injectable({ providedIn: 'root' })
export class ResolutionTargetService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/resolution-target`;

  private readonly cache = inject(QueryCache);
  private readonly fetch = (context: HttpContext) =>
    this.http.get<ResolutionTarget>(this.base, { context });

  get(options?: CacheReadOptions): Observable<ResolutionTarget> {
    return this.cache.read('rules:target', this.fetch, options);
  }

  prefetch(): void {
    this.cache.prefetch('rules:target', this.fetch);
  }

  save(target: ResolutionTarget): Observable<ResolutionTarget> {
    return this.http.put<ResolutionTarget>(this.base, target);
  }
}
