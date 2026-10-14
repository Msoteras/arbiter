import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { SetupCardComponent } from './setup-card.component';
import { OnboardingStep } from '../../../../core/onboarding/onboarding';
import { OnboardingService } from '../../../../core/onboarding/onboarding.service';

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

describe('SetupCardComponent', () => {
  function mount(steps: OnboardingStep[]): HTMLElement {
    TestBed.configureTestingModule({
      imports: [SetupCardComponent],
      providers: [
        provideRouter([]),
        {
          provide: OnboardingService,
          useValue: {
            steps: signal(steps),
            failed: signal(false),
            routeOf: () => ({ path: '/insurer/rules' }),
            requestFocus: () => undefined,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(SetupCardComponent);
    fixture.componentRef.setInput('placement', 'top');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('leaves out the heading of a group with nothing pending', () => {
    const el = mount([
      step({ step: 'ANALYSTS', status: 'PENDING' }),
      step({ step: 'DOCUMENTS', level: 'RECOMMENDED', status: 'DONE' }),
    ]);

    const headings = el.textContent ?? '';
    expect(headings).toContain('Obligatorio');
    expect(headings).not.toContain('Recomendado');
  });

  it('only offers to discard a step that is not required', () => {
    const el = mount([
      step({ step: 'ANALYSTS', status: 'PENDING' }),
      step({ step: 'DOCUMENTS', level: 'RECOMMENDED', status: 'PENDING' }),
    ]);

    const discards = [...el.querySelectorAll('button')].filter(
      (b) => b.textContent?.trim() === 'Descartar',
    );
    expect(discards.length).toBe(1);
  });
});
