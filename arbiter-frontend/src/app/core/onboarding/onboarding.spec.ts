import {
  OnboardingStep,
  onboardingStepRoute,
  onboardingStepSummary,
  onboardingStepTitle,
} from './onboarding';

function step(partial: Partial<OnboardingStep>): OnboardingStep {
  return {
    step: 'COVERAGES',
    level: 'REQUIRED',
    branchId: null,
    branchName: null,
    status: 'PENDING',
    count: null,
    secondaryCount: null,
    ...partial,
  };
}

describe('onboarding texts', () => {
  it('names the branch in the coverages step', () => {
    expect(onboardingStepTitle(step({ branchId: 1, branchName: 'Celulares' }))).toBe(
      'Cargá las coberturas de Celulares',
    );
  });

  it('uses the singular for one and the plural otherwise', () => {
    expect(onboardingStepSummary(step({ count: 1 }))).toBe('1 cobertura');
    expect(onboardingStepSummary(step({ count: 3 }))).toBe('3 coberturas');
    expect(onboardingStepSummary(step({ step: 'AUTHORITIES', count: 1 }))).toBe('Tope en 1 ramo');
  });

  it('adds the analysts still to activate only when there are some', () => {
    expect(onboardingStepSummary(step({ step: 'ANALYSTS', count: 3, secondaryCount: 1 }))).toBe(
      '3 analistas · 1 sin activar',
    );
    expect(onboardingStepSummary(step({ step: 'ANALYSTS', count: 2, secondaryCount: 0 }))).toBe(
      '2 analistas',
    );
  });
});

describe('onboarding routes', () => {
  it('opens a branch tab with the step branch', () => {
    expect(onboardingStepRoute(step({ branchId: 4 }), 1)).toEqual({
      path: '/insurer/rules',
      queryParams: { ramo: '4', solapa: 'coverages' },
    });
  });

  it('opens a branch-level step on the first branch', () => {
    expect(onboardingStepRoute(step({ step: 'FAST_TRACK', level: 'RECOMMENDED' }), 1)).toEqual({
      path: '/insurer/rules',
      queryParams: { ramo: '1', solapa: 'fastTrack' },
    });
  });

  it('opens a general section or the users screen', () => {
    expect(onboardingStepRoute(step({ step: 'SCORING', level: 'RECOMMENDED' }), 1)).toEqual({
      path: '/insurer/rules',
      queryParams: { seccion: 'scoring' },
    });
    expect(onboardingStepRoute(step({ step: 'INSURED' }), 1)).toEqual({ path: '/insurer/users' });
  });
});
