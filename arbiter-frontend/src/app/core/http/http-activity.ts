import { HttpInterceptorFn } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';

import { SILENT_RETRY } from '../cache/query-cache';

/** Counts the requests someone is waiting for; `app-activity-bar` shows while there is one. */
@Injectable({ providedIn: 'root' })
export class HttpActivityService {
  private readonly pending = signal(0);

  readonly active = computed(() => this.pending() > 0);

  start(): void {
    this.pending.update((n) => n + 1);
  }

  end(): void {
    this.pending.update((n) => Math.max(0, n - 1));
  }
}

/**
 * Prefetches and polls carry SILENT_RETRY: nobody is waiting for them, so they stay off the bar.
 * Placed before the retry interceptor, a request asleep on Railway keeps the bar up while it retries.
 */
export const httpActivityInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.context.get(SILENT_RETRY)) return next(req);
  const activity = inject(HttpActivityService);
  activity.start();
  return next(req).pipe(finalize(() => activity.end()));
};
