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
    fixture.detectChanges();
    openPanel(fixture.nativeElement as HTMLElement);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function openPanel(el: HTMLElement): void {
    const open = [...el.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Ver pasos ›',
    );
    open?.click();
  }

  it('shows the completed steps under their group', () => {
    const el = mount([
      step({ step: 'ANALYSTS', status: 'PENDING' }),
      step({ step: 'DOCUMENTS', level: 'RECOMMENDED', status: 'DONE' }),
    ]);

    const text = el.textContent ?? '';
    expect(text).toContain('Obligatorio');
    expect(text).toContain('Recomendado');
    expect(el.querySelectorAll('.step.done').length).toBe(1);
  });

  it('keeps the checklist in a panel, not on the home', () => {
    TestBed.configureTestingModule({
      imports: [SetupCardComponent],
      providers: [
        provideRouter([]),
        {
          provide: OnboardingService,
          useValue: {
            steps: signal([step({ step: 'ANALYSTS', status: 'PENDING' })]),
            failed: signal(false),
            routeOf: () => ({ path: '/insurer/rules' }),
            requestFocus: () => undefined,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(SetupCardComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('0 de 1');
    expect(el.textContent).not.toContain('Obligatorio');
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
