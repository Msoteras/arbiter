import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { UserRole } from '../models/user-role';
import { AuthSessionService } from './auth-session.service';

/**
 * `route.data['roles']` lists the allowed roles; the referent always passes. A disallowed role is
 * redirected to its own home instead of an error page. Without a session it goes to the login,
 * which brings the user back to `returnUrl` (a link from a mail opens a new tab, with no session).
 */
export const roleGuard: CanActivateFn = (route, state) => {
  const session = inject(AuthSessionService);
  const router = inject(Router);

  const current = session.session();
  if (!current) {
    return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
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
