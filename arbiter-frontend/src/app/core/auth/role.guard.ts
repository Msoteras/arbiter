import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { UserRole } from '../models/user-role';
import { AuthSessionService } from './auth-session.service';

/**
 * `route.data['roles']` lists the allowed roles; the referent always passes. A disallowed role is
 * redirected to its own home instead of an error page.
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
