import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  provideRouter,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';

import { AuthSessionService } from './auth-session.service';
import { roleGuard } from './role.guard';

describe('roleGuard', () => {
  it('sends a visitor without a session to the login, keeping where they were going', () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthSessionService, useValue: { session: () => null } },
      ],
    });
    const route = { data: { roles: ['ASEGURADO'] } } as unknown as ActivatedRouteSnapshot;
    const state = { url: '/portal/cases/42?insurer=bbva' } as RouterStateSnapshot;

    const result = TestBed.runInInjectionContext(() => roleGuard(route, state)) as UrlTree;

    expect(result.toString()).toBe('/login?returnUrl=%2Fportal%2Fcases%2F42%3Finsurer%3Dbbva');
  });
});
