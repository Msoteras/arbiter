import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import { AuthSessionService } from '../auth/auth-session.service';

/**
 * Adjunta el JWT de la sesión (login propio, H0001) a las llamadas /api/*.
 * Cuando se integre Auth0 el token sale de ahí en vez de AuthSessionService,
 * pero el resto de la request queda igual.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(AuthSessionService).token();
  if (token && req.url.includes('/api/')) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }
  return next(req);
};
