import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthSessionService } from './auth-session.service';

/**
 * Sends an insured with pending onboarding to the onboarding screen. Runs on top of `roleGuard`
 * for `/portal/*`, except the onboarding route itself (it would redirect to itself).
 */
export const onboardingGuard: CanActivateFn = () => {
  const session = inject(AuthSessionService).session();

  if (!session || session.rol !== 'ASEGURADO') {
    return true;
  }

  // Only false blocks: a null claim must never lock the user out of the portal.
  if (session.onboardingComplete === false) {
    return inject(Router).parseUrl('/portal/onboarding');
  }

  return true;
};

/** Guards the onboarding route itself: once completed, it redirects to the portal home. */
export const onboardingPendingGuard: CanActivateFn = () => {
  const session = inject(AuthSessionService).session();

  if (session?.rol === 'ASEGURADO' && session.onboardingComplete === false) {
    return true;
  }

  return inject(Router).parseUrl('/portal/home');
};
