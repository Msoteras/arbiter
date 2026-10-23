import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { FraudConfigComponent } from './fraud-config.component';
import { FraudRecordRule, FraudRuleService } from '../fraud-rule.service';

describe('FraudConfigComponent', () => {
  function mount(rule: FraudRecordRule): HTMLElement {
    TestBed.configureTestingModule({
      imports: [FraudConfigComponent],
      providers: [
        provideNoopAnimations(),
        // The onboarding target directive reaches OnboardingService, which injects HttpClient.
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: FraudRuleService, useValue: { get: () => of(rule), save: () => of(rule) } },
      ],
    });
    const fixture = TestBed.createComponent(FraudConfigComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  // The visible label: the button also holds its hidden "Guardando" and "Guardado" labels.
  const saveButton = (el: HTMLElement) =>
    [...el.querySelectorAll('button')].find(
      (b) => b.querySelector('.label')?.textContent?.trim() === 'Guardar',
    );

  it('lets a referent save the default policy the insurer never saved', () => {
    const el = mount({ ruleId: null, windowMonths: 36, blocksFastTrack: false });

    expect(saveButton(el)?.disabled).toBeFalse();
  });

  it('keeps save off for a saved policy nobody changed', () => {
    const el = mount({ ruleId: 7, windowMonths: 36, blocksFastTrack: false });

    expect(saveButton(el)?.disabled).toBeTrue();
  });
});
