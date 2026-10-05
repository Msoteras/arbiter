import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ButtonComponent } from './button/button.component';
import { CheckboxComponent } from './checkbox/checkbox.component';
import { ChipGroupComponent } from './chip-group/chip-group.component';
import { InputComponent } from './input/input.component';
import { ModalComponent } from './modal/modal.component';
import { PaginationComponent } from './pagination/pagination.component';
import { SelectComponent } from './select/select.component';
import { TextareaComponent } from './textarea/textarea.component';
import { mountInViewport } from './testing/viewport';

const OPTIONS = [
  { value: 'a', label: 'Robo en vía pública' },
  { value: 'b', label: 'Daño accidental' },
];

@Component({
  imports: [
    ButtonComponent,
    CheckboxComponent,
    ChipGroupComponent,
    InputComponent,
    PaginationComponent,
    SelectComponent,
    TextareaComponent,
  ],
  template: `
    <app-button>Continuar</app-button>
    <app-button variant="secondary" size="sm">Reintentar</app-button>
    <app-input placeholder="Calle y número" />
    <app-textarea />
    <app-select [options]="options" placeholder="Seleccioná una póliza" />
    <app-chip-group [options]="options" />
    <app-chip-group [options]="options" size="sm" />
    <app-checkbox>Acepto el uso de mis imágenes</app-checkbox>
    <app-pagination [totalPages]="3" [totalElements]="27" [size]="10" />
  `,
})
class KitHost {
  readonly options = OPTIONS;
}

@Component({
  imports: [ModalComponent, ButtonComponent],
  template: `
    <app-modal [open]="true" heading="Nueva denuncia">
      <div style="height: 300vh"></div>
      <ng-container modalActions>
        <app-button>Confirmar</app-button>
      </ng-container>
    </app-modal>
  `,
})
class TallModalHost {}

const PHONES = [
  { name: 'iPhone SE', width: 375, height: 667 },
  { name: 'iPhone 12/13/14', width: 390, height: 844 },
  { name: 'Pixel 5', width: 393, height: 851 },
];

const TOUCH_TARGET = 44;

describe('Kit de UI · responsive', () => {
  let frame: HTMLIFrameElement;

  async function mount<T>(host: new () => T, width: number, height: number) {
    await TestBed.configureTestingModule({ imports: [host] }).compileComponents();
    const fixture: ComponentFixture<T> = TestBed.createComponent(host);
    fixture.detectChanges();
    frame = await mountInViewport(fixture, width, height);
    return frame.contentDocument!;
  }

  afterEach(() => frame?.remove());

  for (const phone of PHONES) {
    describe(`${phone.name} (${phone.width}px)`, () => {
      it('makes controls at least 44px tall', async () => {
        const doc = await mount(KitHost, phone.width, phone.height);
        // The checkbox's target is its label row, not the 18px native box.
        const controls = doc.querySelectorAll<HTMLElement>(
          'button, input:not([type="checkbox"]), textarea, select, label.cb',
        );
        const tooSmall = [...controls]
          .filter((el) => el.getBoundingClientRect().height < TOUCH_TARGET)
          .map((el) => `${el.tagName}.${el.className} "${el.textContent?.trim()}"`);

        expect(controls.length).toBeGreaterThan(0);
        expect(tooSmall).toEqual([]);
      });

      it('no genera scroll horizontal', async () => {
        const doc = await mount(KitHost, phone.width, phone.height);

        expect(doc.documentElement.scrollWidth).toBeLessThanOrEqual(phone.width);
      });

      it('scrolls a modal taller than the screen inside and keeps its actions visible', async () => {
        const doc = await mount(TallModalHost, phone.width, phone.height);
        const dialog = doc.querySelector('[role="dialog"]')!.getBoundingClientRect();
        const body = doc.querySelector('.modal-body')!;
        const action = doc.querySelector('.modal-actions button')!.getBoundingClientRect();

        expect(dialog.top).toBeGreaterThanOrEqual(0);
        expect(dialog.bottom).toBeLessThanOrEqual(phone.height);
        expect(body.scrollHeight).toBeGreaterThan(body.clientHeight);
        expect(action.bottom).toBeLessThanOrEqual(phone.height);
      });
    });
  }
});
