import { HttpInterceptorFn } from '@angular/common/http';

/**
 * Stub de autenticación. Cuando integremos Auth0, este interceptor va a
 * adjuntar el JWT (Authorization: Bearer <token>) a las llamadas /api/*.
 * Hoy no hay token, así que pasa la request tal cual — el gancho ya queda puesto.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token: string | null = null; // TODO: token real de Auth0
  if (token && req.url.includes('/api/')) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }
  return next(req);
};
