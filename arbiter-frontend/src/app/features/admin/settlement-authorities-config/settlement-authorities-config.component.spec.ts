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

  function mount(readOnly: boolean, branchId = 1): HTMLElement {
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
    fixture.componentRef.setInput('branchId', branchId);
    fixture.componentRef.setInput('readOnly', readOnly);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function fields(el: HTMLElement): HTMLInputElement[] {
    return [...el.querySelectorAll<HTMLInputElement>('app-input input')];
  }

  it('lets the referente edit the ceiling of the branch', () => {
    const el = mount(false);

    expect(fields(el).map((f) => f.disabled)).toEqual([false]);
    expect(el.querySelector('app-save-bar')).not.toBeNull();
  });

  it('shows only the ceiling of its own branch', () => {
    expect(fields(mount(true, 1)).map((f) => f.value)).toEqual(['1.500.000']);
  });

  it('shows an uncapped branch as empty', () => {
    expect(fields(mount(true, 2)).map((f) => f.value)).toEqual(['']);
  });

  it('shows the ceiling to an analyst with nothing to change or save', () => {
    const el = mount(true);

    expect(fields(el).map((f) => f.disabled)).toEqual([true]);
    expect(el.querySelector('app-save-bar')).toBeNull();
  });
});
