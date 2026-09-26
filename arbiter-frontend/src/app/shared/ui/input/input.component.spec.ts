import { ComponentFixture, TestBed } from '@angular/core/testing';

import { InputComponent } from './input.component';

/** The native `min` covers the arrows and submit, but does not stop typing or pasting "-500". */
describe('InputComponent · numeric without negatives', () => {
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

  function pressMinus(): boolean {
    const event = new KeyboardEvent('keydown', { key: '-', cancelable: true, bubbles: true });
    field().dispatchEvent(event);
    fixture.detectChanges();
    return event.defaultPrevented;
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
    paste('-guión al principio');
    expect(fixture.componentInstance.value()).toBe('-guión al principio');
  });
});
