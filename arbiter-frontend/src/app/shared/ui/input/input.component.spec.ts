import { ComponentFixture, TestBed } from '@angular/core/testing';

import { InputComponent } from './input.component';

/**
 * Lo único del componente con lógica propia: el campo numérico que no admite negativos. El `min`
 * nativo cubre las flechas y el submit, pero no impide tipear "-500", y ahí el valor negativo
 * llegaba al modelo.
 */
describe('InputComponent · numérico sin negativos', () => {
  let fixture: ComponentFixture<InputComponent>;

  function field(): HTMLInputElement {
    return fixture.nativeElement.querySelector('.field') as HTMLInputElement;
  }

  /** Pegar/autocompletar: llega por `input`, nunca pasó por `keydown`. */
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

  it('bloquea el signo menos tipeado', () => {
    expect(pressMinus()).toBeTrue();
  });

  it('descarta el signo de un valor pegado y se queda con el número', () => {
    paste('-500');

    expect(fixture.componentInstance.value()).toBe('500');
    expect(field().value).toBe('500');
  });

  it('no toca los valores positivos', () => {
    paste('1500.50');

    expect(fixture.componentInstance.value()).toBe('1500.50');
  });

  it('sin min, el campo numérico sigue admitiendo negativos (ej. un delta)', () => {
    fixture.componentRef.setInput('min', null);
    fixture.detectChanges();

    expect(pressMinus()).toBeFalse();
    paste('-500');
    expect(fixture.componentInstance.value()).toBe('-500');
  });

  it('no se mete con los campos de texto', () => {
    fixture.componentRef.setInput('type', 'text');
    fixture.componentRef.setInput('min', 0);
    fixture.detectChanges();

    expect(pressMinus()).toBeFalse();
    paste('-guión al principio');
    expect(fixture.componentInstance.value()).toBe('-guión al principio');
  });
});
