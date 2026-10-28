import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { ResolutionTargetConfigComponent } from './resolution-target-config.component';
import { ResolutionTargetService } from '../resolution-target.service';

describe('ResolutionTargetConfigComponent', () => {
  function mount(readOnly: boolean): HTMLElement {
    TestBed.configureTestingModule({
      imports: [ResolutionTargetConfigComponent],
      providers: [
        provideNoopAnimations(),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ResolutionTargetService,
          useValue: { get: () => of({ enabled: true, targetDays: 20 }) },
        },
      ],
    });
    const fixture = TestBed.createComponent(ResolutionTargetConfigComponent);
    fixture.componentRef.setInput('readOnly', readOnly);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('lets the referente change the target', () => {
    const el = mount(false);

    expect(el.querySelector<HTMLButtonElement>('[role="switch"]')?.disabled).toBeFalse();
    expect(el.querySelector<HTMLInputElement>('app-input input')?.disabled).toBeFalse();
    expect(el.querySelector('app-save-bar')).not.toBeNull();
  });

  it('shows the target to an analyst with nothing to change or save', () => {
    const el = mount(true);

    expect(el.querySelector<HTMLButtonElement>('[role="switch"]')?.disabled).toBeTrue();
    expect(el.querySelector<HTMLInputElement>('app-input input')?.value).toBe('20');
    expect(el.querySelector<HTMLInputElement>('app-input input')?.disabled).toBeTrue();
    expect(el.querySelector('app-save-bar')).toBeNull();
  });
});
