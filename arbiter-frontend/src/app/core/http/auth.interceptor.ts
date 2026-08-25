import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthSessionService } from '../auth/auth-session.service';

/**
 * Adjunta el JWT de la sesión (login propio, H0001) a las llamadas /api/*.
 * Cuando se integre Auth0 el token sale de ahí en vez de AuthSessionService,
 * pero el resto de la request queda igual.
 *
 * También maneja el 401 global: sin esto, un token vencido (60 min, H0001) hacía que
 * cada pantalla mostrara su propio error genérico sin decirle al usuario que tenía que
 * volver a loguearse.
 */
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
      // El login manda su propio 401 (credenciales inválidas) — eso no es una sesión
      // vencida, así que no dispara el logout automático.
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
