import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { CheckboxComponent } from './checkbox.component';

@Component({
  imports: [CheckboxComponent],
  template: `
    <app-checkbox [checked]="true" [disabled]="true">Caída</app-checkbox>
    <app-checkbox [checked]="false" [disabled]="true">Hurto</app-checkbox>
  `,
})
class Host {}

describe('CheckboxComponent', () => {
  it('tells a ticked box from an empty one while disabled', () => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();

    const [ticked, empty] = [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLInputElement>('input'),
    ];
    expect(ticked.disabled).toBeTrue();
    expect(getComputedStyle(ticked).backgroundColor).not.toBe(
      getComputedStyle(empty).backgroundColor,
    );
  });
});
