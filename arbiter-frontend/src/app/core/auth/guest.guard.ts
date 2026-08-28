import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { homeRouteFor } from '../models/user-role';
import { AuthSessionService } from './auth-session.service';

/**
 * Con sesión abierta, /login rebota al home del rol. La ruta comodín manda ahí toda URL
 * desconocida, así que sin esto un link viejo dejaba al usuario logueado mirando el formulario.
 */
export const guestGuard: CanActivateFn = () => {
  const session = inject(AuthSessionService).session();
  if (!session) {
    return true;
  }
  return inject(Router).parseUrl(homeRouteFor(session.rol));
};
