import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { retry, throwError, timer } from 'rxjs';

import { ToastService } from '../../shared/ui/toast/toast.service';

/** Set by nginx (nginx.conf.template) only when the request never reached the backend. */
export const UPSTREAM_UNREACHABLE_HEADER = 'X-Upstream-Unreachable';

/** ~20 s in total: a backend asleep on Railway takes 5-7 s to boot. */
export const RETRY_DELAYS_MS = [1000, 2000, 4000, 6000, 7000];

const NOTICE_INTERVAL_MS = 30_000;

@Injectable({ providedIn: 'root' })
export class WakingNotice {
  private readonly toast = inject(ToastService);
  private shownAt = -Infinity;

  show(): void {
    const now = Date.now();
    if (now - this.shownAt < NOTICE_INTERVAL_MS) return;
    this.shownAt = now;
    this.toast.show('El sistema se está iniciando, puede tardar unos segundos.', 'info');
  }
}

/** Retrying any method is safe here: a request that never arrived can't have run twice. */
export const upstreamRetryInterceptor: HttpInterceptorFn = (req, next) => {
  const notice = inject(WakingNotice);
  return next(req).pipe(
    retry({
      count: RETRY_DELAYS_MS.length,
      delay: (error: unknown, retryCount: number) => {
        if (!neverReachedTheBackend(error)) return throwError(() => error);
        notice.show();
        return timer(RETRY_DELAYS_MS[retryCount - 1]);
      },
    }),
  );
};

function neverReachedTheBackend(error: unknown): boolean {
  return (
    error instanceof HttpErrorResponse &&
    error.status === 503 &&
    error.headers.has(UPSTREAM_UNREACHABLE_HEADER)
  );
}
