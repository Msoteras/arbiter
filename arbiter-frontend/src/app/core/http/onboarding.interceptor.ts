import { HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { tap } from 'rxjs';

import { OnboardingService } from '../onboarding/onboarding.service';

/** Writes to the sections the setup checklist watches. */
function savesSetup(url: string): boolean {
  if (url.includes('/rules/onboarding')) {
    return false;
  }
  return (
    url.includes('/rules/') ||
    url.includes('/coverages') ||
    url.includes('/expert-firms') ||
    url.includes('/settlement-authorities') ||
    url.includes('/auth/users')
  );
}

/**
 * After a successful save of a setup section, refreshes the referent's checklist, so a completed
 * step is announced without each screen having to remember to do it.
 */
export const onboardingInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.method === 'GET' || !savesSetup(req.url)) {
    return next(req);
  }
  const onboarding = inject(OnboardingService);
  return next(req).pipe(
    tap((event) => {
      if (event instanceof HttpResponse) {
        onboarding.refreshAfterSave();
      }
    }),
  );
};
