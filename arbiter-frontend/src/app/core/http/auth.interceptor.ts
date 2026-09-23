import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthSessionService } from '../auth/auth-session.service';

/** Attaches the session JWT to /api/* calls and sends the user to login on an expired session. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const session = inject(AuthSessionService);
  const router = inject(Router);
  const token = session.token();
  const isApiCall = req.url.includes('/api/');
  if (token && isApiCall) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }
  return next(req).pipe(
    catchError((err: unknown) => {
      // A 401 from login itself means bad credentials, not an expired session.
      if (
        err instanceof HttpErrorResponse &&
        err.status === 401 &&
        isApiCall &&
        !req.url.includes('/auth/login')
      ) {
        session.clear();
        router.navigate(['/login'], { queryParams: { sessionExpired: 1 } });
      }
      return throwError(() => err);
    }),
  );
};
