import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { SettlementAuthoritiesConfigComponent } from './settlement-authorities-config.component';
import {
  SettlementAuthority,
  SettlementAuthoritiesService,
} from '../settlement-authorities.service';

describe('SettlementAuthoritiesConfigComponent', () => {
  const AUTHORITIES: SettlementAuthority[] = [
    { branchId: 1, branchName: 'Celulares', maxAmount: 1500000, updatedAt: null },
    { branchId: 2, branchName: 'Tecnología Portátil', maxAmount: null, updatedAt: null },
  ];

  function mount(readOnly: boolean): HTMLElement {
    TestBed.configureTestingModule({
      imports: [SettlementAuthoritiesConfigComponent],
      providers: [
        provideNoopAnimations(),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: SettlementAuthoritiesService,
          useValue: { list: () => of(AUTHORITIES), set: () => of(undefined) },
        },
      ],
    });
    const fixture = TestBed.createComponent(SettlementAuthoritiesConfigComponent);
    fixture.componentRef.setInput('readOnly', readOnly);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function fields(el: HTMLElement): HTMLInputElement[] {
    return [...el.querySelectorAll<HTMLInputElement>('app-input input')];
  }

  it('lets the referente edit each ceiling', () => {
    const el = mount(false);

    expect(fields(el).map((f) => f.disabled)).toEqual([false, false]);
    expect(el.querySelector('app-save-bar')).not.toBeNull();
  });

  it('shows each ceiling to an analyst with nothing to change or save', () => {
    const el = mount(true);

    expect(fields(el).map((f) => f.value)).toEqual(['1.500.000', '']);
    expect(fields(el).map((f) => f.disabled)).toEqual([true, true]);
    expect(el.querySelector('app-save-bar')).toBeNull();
  });
});
