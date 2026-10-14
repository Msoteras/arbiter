import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { OnboardingStep } from './onboarding';
import { OnboardingService } from './onboarding.service';
import { ToastService } from '../../shared/ui/toast/toast.service';

function step(partial: Partial<OnboardingStep>): OnboardingStep {
  return {
    step: 'ANALYSTS',
    level: 'REQUIRED',
    branchId: null,
    branchName: null,
    status: 'PENDING',
    count: null,
    secondaryCount: null,
    ...partial,
  };
}

describe('OnboardingService focus', () => {
  let service: OnboardingService;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(OnboardingService);
    router = TestBed.inject(Router);
  });

  it('lets only the first control of the requested step claim it', () => {
    service.requestFocus('ANALYSTS');

    expect(service.claimFocus('INSURED')).toBeFalse();
    expect(service.claimFocus('ANALYSTS')).toBeTrue();
    expect(service.claimFocus('ANALYSTS')).toBeFalse();
  });

  it('points at the same step again on a new request', () => {
    service.requestFocus('ANALYSTS');
    service.claimFocus('ANALYSTS');

    service.requestFocus('ANALYSTS');

    expect(service.claimFocus('ANALYSTS')).toBeTrue();
  });

  it('ignores a request older than the screen can take to load', () => {
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(2026, 9, 12, 12, 0, 0));
    service.requestFocus('ANALYSTS');

    jasmine.clock().tick(16_000);

    expect(service.claimFocus('ANALYSTS')).toBeFalse();
    jasmine.clock().uninstall();
  });

  it('flags a step whose screen is already open at that address', () => {
    spyOnProperty(router, 'url').and.returnValue('/insurer/users');
    spyOn(router, 'navigateByUrl').and.resolveTo(true);

    service.open(step({ step: 'INSURED' }));

    expect(service.focusRequest()).toEqual(
      jasmine.objectContaining({ step: 'INSURED', sameUrl: true }),
    );
  });

  it('does not flag a step on another screen', () => {
    spyOnProperty(router, 'url').and.returnValue('/insurer/rules?seccion=scoring');
    spyOn(router, 'navigateByUrl').and.resolveTo(true);

    service.open(step({ step: 'INSURED' }));

    expect(service.focusRequest()?.sameUrl).toBeFalse();
    expect(router.navigateByUrl).toHaveBeenCalled();
  });

  /** A coverage save fires several requests; a read still on its way must not race another one. */
  it('announces a completed step once, however many saves land while it reads', fakeAsync(() => {
    const http = TestBed.inject(HttpTestingController);
    const show = spyOn(TestBed.inject(ToastService), 'show');
    const url = (r: { url: string }) => r.url.endsWith('/rules/onboarding');
    const pending = [step({ step: 'ANALYSTS', status: 'PENDING' })];
    const done = [step({ step: 'ANALYSTS', status: 'DONE' })];

    service.load();
    http.expectOne(url).flush({ steps: pending });

    service.refreshAfterSave();
    tick(500);
    const first = http.expectOne(url);
    service.refreshAfterSave();
    tick(500);
    // The second save waits for the first read instead of starting its own.
    http.expectNone(url);

    first.flush({ steps: done });
    http.expectOne(url).flush({ steps: done });

    expect(show.calls.count()).toBe(1);
    http.verify();
  }));
});
