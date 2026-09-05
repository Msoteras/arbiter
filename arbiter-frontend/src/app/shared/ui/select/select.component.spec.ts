import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SelectComponent, SelectOption } from './select.component';

/**
 * El buscador del select es lo único del componente con lógica propia (filtrado + tope de
 * resultados); el resto es apertura/posicionamiento del panel, que se ve a ojo.
 */
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

  function open(): void {
    (fixture.nativeElement.querySelector('.trigger') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  function type(text: string): void {
    const input = fixture.nativeElement.querySelector('.search') as HTMLInputElement;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function renderedLabels(): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.option')).map((li) =>
      (li as HTMLElement).textContent!.trim(),
    );
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [SelectComponent] }).compileComponents();
    fixture = TestBed.createComponent(SelectComponent);
    fixture.componentRef.setInput('options', provinces);
    fixture.componentRef.setInput('searchable', true);
    fixture.componentRef.setInput('placeholder', 'Provincia');
    fixture.detectChanges();
  });

  it('sin buscador dibuja el placeholder como opción vacía más el catálogo entero', () => {
    fixture.componentRef.setInput('searchable', false);
    fixture.detectChanges();
    open();

    expect(renderedLabels()).toEqual(['Provincia', ...provinces.map((o) => o.label)]);
  });

  it('filtra ignorando acentos y mayúsculas', () => {
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

  it('elige la opción activa con Enter y deja el valor elegido', () => {
    open();
    type('tucu');
    const input = fixture.nativeElement.querySelector('.search') as HTMLInputElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    fixture.detectChanges();

    expect(fixture.componentInstance.value()).toBe('Tucumán');
    expect(fixture.nativeElement.querySelector('.panel')).toBeNull();
  });

  it('el espacio se escribe en el buscador en vez de elegir la opción activa', () => {
    open();
    type('entre');
    const input = fixture.nativeElement.querySelector('.search') as HTMLInputElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
    fixture.detectChanges();

    expect(fixture.componentInstance.value()).toBe('');
    expect(fixture.nativeElement.querySelector('.panel')).not.toBeNull();
  });

  it('reabre con el listado completo: lo tipeado no sobrevive al cierre', () => {
    open();
    type('cordoba');
    fixture.componentInstance.value.set('Córdoba');
    (fixture.nativeElement.querySelector('.option') as HTMLElement).click();
    fixture.detectChanges();
    open();

    expect(renderedLabels().length).toBe(provinces.length + 1);
  });
});
