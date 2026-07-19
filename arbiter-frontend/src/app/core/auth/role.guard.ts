import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { UserRole } from '../models/user-role';
import { AuthSessionService } from './auth-session.service';

/**
 * H0003 - RBAC en el frontend. `route.data['roles']` lista los roles permitidos para esa ruta;
 * el referente de aseguradora siempre pasa (acceso total). Sin sesión → /login. Rol no permitido
 * → al home del rol que sí tiene (no un error, para no dejar al usuario varado).
 */
export const roleGuard: CanActivateFn = (route) => {
  const session = inject(AuthSessionService);
  const router = inject(Router);

  const current = session.session();
  if (!current) {
    return router.parseUrl('/login');
  }

  if (current.rol === 'REFERENTE_ASEGURADORA') {
    return true;
  }

  const allowedRoles = route.data['roles'] as UserRole[] | undefined;
  if (!allowedRoles || allowedRoles.includes(current.rol)) {
    return true;
  }

  return router.parseUrl(current.rol === 'ASEGURADO' ? '/portal' : '/inbox');
};
