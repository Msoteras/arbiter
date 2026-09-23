import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { homeRouteFor } from '../models/user-role';
import { AuthSessionService } from './auth-session.service';

/** With an open session, /login (also the wildcard route's target) redirects to the role's home. */
export const guestGuard: CanActivateFn = () => {
  const session = inject(AuthSessionService).session();
  if (!session) {
    return true;
  }
  return inject(Router).parseUrl(homeRouteFor(session.rol));
};
