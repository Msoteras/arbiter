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

  it('el campo es un input de texto, no un botón', () => {
    expect(field().tagName).toBe('INPUT');
    expect(field().getAttribute('role')).toBe('combobox');
  });

  it('sin searchable el campo vuelve a ser un botón con el listado entero', () => {
    fixture.componentRef.setInput('searchable', false);
    fixture.detectChanges();
    expect(field().tagName).toBe('BUTTON');

    open();
    expect(renderedLabels()).toEqual(['Provincia', ...provinces.map((o) => o.label)]);
  });

  it('con un clic despliega el listado completo y deja el foco en el campo', () => {
    open();

    expect(isOpen()).toBeTrue();
    expect(renderedLabels().length).toBe(provinces.length + 1);
    expect(document.activeElement).toBe(field());
  });

  it('filtra ignorando acentos y mayúsculas mientras se escribe', () => {
    open();
    type('cordoba');

    expect(renderedLabels()).toEqual(['Córdoba']);
  });

  it('matchea por cualquier parte del label, no solo el prefijo', () => {
    open();
    type('rio');

    expect(renderedLabels()).toEqual(['Entre Ríos', 'Río Negro']);
  });

  it('saca el placeholder del listado mientras se busca: es la opción vacía, no un resultado', () => {
    open();
    type('a');

    expect(renderedLabels()).not.toContain('Provincia');
  });

  it('avisa cuando hay más resultados de los que dibuja', () => {
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

  it('elige con Enter la primera coincidencia y muestra su label en el campo', () => {
    open();
    type('tucu');
    press('Enter');

    expect(fixture.componentInstance.value()).toBe('Tucumán');
    expect(isOpen()).toBeFalse();
    expect(field().value).toBe('Tucumán');
  });

  it('elige con clic en la opción', () => {
    open();
    type('entre');
    (fixture.nativeElement.querySelector('.option') as HTMLElement).click();
    fixture.detectChanges();

    expect(fixture.componentInstance.value()).toBe('Entre Ríos');
    expect(field().value).toBe('Entre Ríos');
  });

  it('el espacio se escribe en la búsqueda en vez de elegir la opción activa', () => {
    open();
    type('entre');
    press(' ');

    expect(fixture.componentInstance.value()).toBe('');
    expect(isOpen()).toBeTrue();
  });

  it('descarta lo tipeado al cerrar sin elegir: el campo no guarda texto libre', () => {
    fixture.componentInstance.value.set('Santa Fe');
    fixture.detectChanges();
    open();
    type('cualquier cosa');
    press('Escape');

    expect(fixture.componentInstance.value()).toBe('Santa Fe');
    expect(field().value).toBe('Santa Fe');
  });

  it('reabre con el listado completo: lo tipeado no sobrevive al cierre', () => {
    open();
    type('cordoba');
    press('Escape');
    open();

    expect(field().value).toBe('');
    expect(renderedLabels().length).toBe(provinces.length + 1);
  });

  it('un clic sobre el campo ya abierto no borra lo que se venía escribiendo', () => {
    open();
    type('cor');
    field().click();
    fixture.detectChanges();

    expect(field().value).toBe('cor');
    expect(renderedLabels()).toEqual(['Córdoba']);
  });

  describe('escribir sobre el campo cerrado', () => {
    it('abre el panel ya filtrado por la letra tipeada', () => {
      press('c');

      expect(isOpen()).toBeTrue();
      expect(field().value).toBe('c');
      expect(renderedLabels()).toEqual(['Córdoba', 'Tucumán']);
    });

    it('deja activa la primera coincidencia, no la opción ya elegida', () => {
      fixture.componentInstance.value.set('Santa Fe');
      fixture.detectChanges();
      press('t');

      const active = fixture.nativeElement.querySelector('.option.active') as HTMLElement;
      expect(active.textContent!.trim()).toBe('Entre Ríos');
    });

    it('no se dispara con teclas de control ni con atajos del navegador', () => {
      press('F5');
      expect(isOpen()).toBeFalse();

      press('v', { ctrlKey: true });
      expect(isOpen()).toBeFalse();
    });

    it('el espacio abre el listado completo en vez de buscar por " "', () => {
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

    it('no marca nada antes de que el usuario pase por el campo', () => {
      expect(errorMessage()).toBeNull();
      expect(field().classList).not.toContain('is-invalid');
    });

    it('el clic afuera cierra el panel y deja el campo marcado como obligatorio', () => {
      open();
      clickOutside();

      expect(isOpen()).toBeFalse();
      expect(errorMessage()).toBe('Elegí la provincia donde pasó.');
      expect(field().classList).toContain('is-invalid');
      expect(field().getAttribute('aria-invalid')).toBe('true');
    });

    it('cierra aunque el click no burbujee hasta document (adentro de un modal)', () => {
      open();
      clickOutsideInsideModal();

      expect(isOpen()).toBeFalse();
      expect(errorMessage()).toBe('Elegí la provincia donde pasó.');
    });

    it('elegir una opción levanta la marca', () => {
      open();
      clickOutside();
      open();
      type('cordoba');
      press('Enter');

      expect(fixture.componentInstance.value()).toBe('Córdoba');
      expect(errorMessage()).toBeNull();
    });

    it('sin required, cerrar sin elegir no marca nada', () => {
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
