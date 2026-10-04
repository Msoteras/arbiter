import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SelectComponent, SelectOption } from './select.component';

describe('SelectComponent · searchable', () => {
  let fixture: ComponentFixture<SelectComponent>;

  const provinces: SelectOption[] = [
    'Buenos Aires',
    'Córdoba',
    'Entre Ríos',
    'Río Negro',
    'Santa Fe',
    'Tucumán',
  ].map((name) => ({ value: name, label: name }));

  function field(): HTMLInputElement {
    return fixture.nativeElement.querySelector('.trigger') as HTMLInputElement;
  }

  function open(): void {
    field().click();
    fixture.detectChanges();
  }

  function type(text: string): void {
    const input = field();
    input.value = text;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function press(key: string, init: KeyboardEventInit = {}): void {
    field().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, ...init }));
    fixture.detectChanges();
  }

  function renderedLabels(): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.option')).map((li) =>
      (li as HTMLElement).textContent!.trim(),
    );
  }

  function isOpen(): boolean {
    return fixture.nativeElement.querySelector('.panel') !== null;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [SelectComponent] }).compileComponents();
    fixture = TestBed.createComponent(SelectComponent);
    fixture.componentRef.setInput('options', provinces);
    fixture.componentRef.setInput('searchable', true);
    fixture.componentRef.setInput('placeholder', 'Provincia');
    fixture.detectChanges();
  });

  it('the field is a text input, not a button', () => {
    expect(field().tagName).toBe('INPUT');
    expect(field().getAttribute('role')).toBe('combobox');
  });

  it('without searchable the field is a button with the full list', () => {
    fixture.componentRef.setInput('searchable', false);
    fixture.detectChanges();
    expect(field().tagName).toBe('BUTTON');

    open();
    expect(renderedLabels()).toEqual(['Provincia', ...provinces.map((o) => o.label)]);
  });

  it('opens the full list on click and keeps focus on the field', () => {
    open();

    expect(isOpen()).toBeTrue();
    expect(renderedLabels().length).toBe(provinces.length + 1);
    expect(document.activeElement).toBe(field());
  });

  it('filters ignoring accents and case while typing', () => {
    open();
    type('cordoba');

    expect(renderedLabels()).toEqual(['Córdoba']);
  });

  it('matches any part of the label, not only the prefix', () => {
    open();
    type('rio');

    expect(renderedLabels()).toEqual(['Entre Ríos', 'Río Negro']);
  });

  it('drops the placeholder while searching: it is the empty option, not a result', () => {
    open();
    type('a');

    expect(renderedLabels()).not.toContain('Provincia');
  });

  it('says when there are more results than it renders', () => {
    const many = Array.from({ length: 150 }, (_, i) => ({
      value: `loc-${i}`,
      label: `Localidad ${i}`,
    }));
    fixture.componentRef.setInput('options', many);
    fixture.detectChanges();
    open();
    type('Localidad');

    expect(renderedLabels().length).toBe(100);
    expect((fixture.nativeElement.querySelector('.hint') as HTMLElement).textContent).toContain(
      '+50',
    );
  });

  it('Enter picks the first match and shows its label in the field', () => {
    open();
    type('tucu');
    press('Enter');

    expect(fixture.componentInstance.value()).toBe('Tucumán');
    expect(isOpen()).toBeFalse();
    expect(field().value).toBe('Tucumán');
  });

  it('picks an option on click', () => {
    open();
    type('entre');
    (fixture.nativeElement.querySelector('.option') as HTMLElement).click();
    fixture.detectChanges();

    expect(fixture.componentInstance.value()).toBe('Entre Ríos');
    expect(field().value).toBe('Entre Ríos');
  });

  it('space is typed into the search instead of picking the active option', () => {
    open();
    type('entre');
    press(' ');

    expect(fixture.componentInstance.value()).toBe('');
    expect(isOpen()).toBeTrue();
  });

  it('discards typed text when closing without picking: the field keeps no free text', () => {
    fixture.componentInstance.value.set('Santa Fe');
    fixture.detectChanges();
    open();
    type('cualquier cosa');
    press('Escape');

    expect(fixture.componentInstance.value()).toBe('Santa Fe');
    expect(field().value).toBe('Santa Fe');
  });

  it('reopens with the full list: typed text does not survive closing', () => {
    open();
    type('cordoba');
    press('Escape');
    open();

    expect(field().value).toBe('');
    expect(renderedLabels().length).toBe(provinces.length + 1);
  });

  it('a click on the open field keeps what was being typed', () => {
    open();
    type('cor');
    field().click();
    fixture.detectChanges();

    expect(field().value).toBe('cor');
    expect(renderedLabels()).toEqual(['Córdoba']);
  });

  describe('typing on the closed field', () => {
    it('opens the panel already filtered by the typed letter', () => {
      press('c');

      expect(isOpen()).toBeTrue();
      expect(field().value).toBe('c');
      expect(renderedLabels()).toEqual(['Córdoba', 'Tucumán']);
    });

    it('activates the first match, not the already selected option', () => {
      fixture.componentInstance.value.set('Santa Fe');
      fixture.detectChanges();
      press('t');

      const active = fixture.nativeElement.querySelector('.option.active') as HTMLElement;
      expect(active.textContent!.trim()).toBe('Entre Ríos');
    });

    it('is not triggered by control keys or browser shortcuts', () => {
      press('F5');
      expect(isOpen()).toBeFalse();

      press('v', { ctrlKey: true });
      expect(isOpen()).toBeFalse();
    });

    it('space opens the full list instead of searching for " "', () => {
      press(' ');

      expect(field().value).toBe('');
      expect(renderedLabels().length).toBe(provinces.length + 1);
    });
  });

  describe('required', () => {
    function errorMessage(): string | null {
      const el = fixture.nativeElement.querySelector('.error-msg') as HTMLElement | null;
      return el ? el.textContent!.trim() : null;
    }

    function clickOutside(): void {
      document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      fixture.detectChanges();
    }

    /** Outside click that never bubbles to document, as inside an app-modal. */
    function clickOutsideInsideModal(): void {
      const dialog = document.createElement('div');
      dialog.addEventListener('click', (e) => e.stopPropagation());
      const otherField = document.createElement('button');
      dialog.appendChild(otherField);
      document.body.appendChild(dialog);
      otherField.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      dialog.remove();
      fixture.detectChanges();
    }

    beforeEach(() => {
      fixture.componentRef.setInput('required', true);
      fixture.componentRef.setInput('requiredMessage', 'Elegí la provincia donde pasó.');
      fixture.detectChanges();
    });

    it('flags nothing before the user visits the field', () => {
      expect(errorMessage()).toBeNull();
      expect(field().classList).not.toContain('is-invalid');
    });

    it('an outside click closes the panel and flags the field as required', () => {
      open();
      clickOutside();

      expect(isOpen()).toBeFalse();
      expect(errorMessage()).toBe('Elegí la provincia donde pasó.');
      expect(field().classList).toContain('is-invalid');
      expect(field().getAttribute('aria-invalid')).toBe('true');
    });

    it('closes even if the click does not bubble to document (inside a modal)', () => {
      open();
      clickOutsideInsideModal();

      expect(isOpen()).toBeFalse();
      expect(errorMessage()).toBe('Elegí la provincia donde pasó.');
    });

    it('picking an option clears the flag', () => {
      open();
      clickOutside();
      open();
      type('cordoba');
      press('Enter');

      expect(fixture.componentInstance.value()).toBe('Córdoba');
      expect(errorMessage()).toBeNull();
    });

    it('without required, closing without picking flags nothing', () => {
      fixture.componentRef.setInput('required', false);
      fixture.detectChanges();
      open();
      clickOutside();

      expect(isOpen()).toBeFalse();
      expect(errorMessage()).toBeNull();
    });
  });
});

@Component({
  imports: [SelectComponent],
  template: `<label>Risk <app-select [options]="options" [(value)]="value" /></label>`,
})
class LabelHostComponent {
  options: SelectOption[] = [
    { value: 'LOW', label: 'Bajo' },
    { value: 'HIGH', label: 'Alto' },
    { value: 'CRITICAL', label: 'Crítico' },
  ];
  value = '';
}

describe('SelectComponent · inside a label', () => {
  it('stays closed after picking an option', async () => {
    await TestBed.configureTestingModule({ imports: [LabelHostComponent] }).compileComponents();
    const fixture = TestBed.createComponent(LabelHostComponent);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.trigger') as HTMLElement).click();
    fixture.detectChanges();
    const options = fixture.nativeElement.querySelectorAll('.option');
    (options[options.length - 1] as HTMLElement).click();
    fixture.detectChanges();

    expect(fixture.componentInstance.value).toBe('CRITICAL');
    expect(fixture.nativeElement.querySelector('.panel')).toBeNull();
  });
});
