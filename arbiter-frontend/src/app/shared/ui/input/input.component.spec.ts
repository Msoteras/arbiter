import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { InputComponent } from './input.component';
import { amountInputDisplay, amountInputLabel, amountInputValue } from '../../../core/util/money';

/** The native `min` covers the arrows and submit, but does not stop typing or pasting "-500". */
describe('InputComponent · numeric fields', () => {
  let fixture: ComponentFixture<InputComponent>;

  function field(): HTMLInputElement {
    return fixture.nativeElement.querySelector('.field') as HTMLInputElement;
  }

  /** Paste/autofill arrives through `input` and never goes through `keydown`. */
  function paste(text: string): void {
    field().value = text;
    field().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function press(key: string): boolean {
    const event = new KeyboardEvent('keydown', { key, cancelable: true, bubbles: true });
    field().dispatchEvent(event);
    fixture.detectChanges();
    return event.defaultPrevented;
  }

  function pressMinus(): boolean {
    return press('-');
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [InputComponent] }).compileComponents();
    fixture = TestBed.createComponent(InputComponent);
    fixture.componentRef.setInput('type', 'number');
    fixture.componentRef.setInput('min', 0);
    fixture.detectChanges();
  });

  it('blocks a typed minus sign', () => {
    expect(pressMinus()).toBeTrue();
  });

  it('drops the sign of a pasted value and keeps the number', () => {
    paste('-500');

    expect(fixture.componentInstance.value()).toBe('500');
    expect(field().value).toBe('500');
  });

  it('leaves positive values alone', () => {
    paste('1500.50');

    expect(fixture.componentInstance.value()).toBe('1500.50');
  });

  it('blocks a typed exponent or plus sign, which the browser accepts as a number', () => {
    expect(press('e')).toBeTrue();
    expect(press('E')).toBeTrue();
    expect(press('+')).toBeTrue();
  });

  it('drops the exponent of a pasted value', () => {
    paste('1e5');

    expect(fixture.componentInstance.value()).toBe('15');
    expect(field().value).toBe('15');
  });

  it('without min, a numeric field still accepts negatives (e.g. a delta)', () => {
    fixture.componentRef.setInput('min', null);
    fixture.detectChanges();

    expect(pressMinus()).toBeFalse();
    paste('-500');
    expect(fixture.componentInstance.value()).toBe('-500');
  });

  it('leaves text fields alone', () => {
    fixture.componentRef.setInput('type', 'text');
    fixture.componentRef.setInput('min', 0);
    fixture.detectChanges();

    expect(pressMinus()).toBeFalse();
    expect(press('e')).toBeFalse();
    paste('-guión al principio');
    expect(fixture.componentInstance.value()).toBe('-guión al principio');
  });
});

@Component({
  imports: [InputComponent],
  template: `<app-input
    [value]="amountInputLabel(amount())"
    [normalize]="amountInputDisplay"
    (valueChange)="amount.set(amountInputValue($event))"
  />`,
})
class AmountHostComponent {
  readonly amount = signal('');
  protected readonly amountInputLabel = amountInputLabel;
  protected readonly amountInputValue = amountInputValue;
  protected readonly amountInputDisplay = amountInputDisplay;
}

describe('InputComponent · formatted amount', () => {
  let fixture: ComponentFixture<AmountHostComponent>;

  function type(text: string): HTMLInputElement {
    const field = fixture.nativeElement.querySelector('.field') as HTMLInputElement;
    field.value = text;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    return field;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AmountHostComponent] }).compileComponents();
    fixture = TestBed.createComponent(AmountHostComponent);
    fixture.detectChanges();
  });

  it('does not keep showing a character it rejected', () => {
    type('15');
    const field = type('15e');

    expect(fixture.componentInstance.amount()).toBe('15');
    expect(field.value).toBe('15');
  });
});
