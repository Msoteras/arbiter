import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { InputComponent } from '../input/input.component';
import { FieldComponent } from './field.component';

@Component({
  imports: [FieldComponent, InputComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-field label="Teléfono" for="phone" [error]="error()">
      <app-input id="phone" />
    </app-field>
    <app-field label="Ramos">
      <input type="checkbox" />
    </app-field>
  `,
})
class HostComponent {
  readonly error = signal<string | null>(null);
}

describe('FieldComponent', () => {
  let fixture: ComponentFixture<HostComponent>;

  const el = () => fixture.nativeElement as HTMLElement;
  const control = () => el().querySelector('input#phone') as HTMLInputElement;

  beforeEach(() => {
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  });

  it('labels the control inside, not its host', () => {
    const label = el().querySelector('label[for="phone"]') as HTMLLabelElement;
    expect(label.control).toBe(control());
    expect(el().querySelectorAll('#phone').length).toBe(1);
  });

  it('marks the control invalid and points it at the error while there is one', () => {
    expect(control().getAttribute('aria-invalid')).toBeNull();

    fixture.componentInstance.error.set('Revisá el teléfono: parece incompleto.');
    fixture.detectChanges();

    expect(control().getAttribute('aria-invalid')).toBe('true');
    const error = el().querySelector('#' + control().getAttribute('aria-describedby'));
    expect(error?.textContent).toContain('Revisá el teléfono');
  });

  it('names a field without a control id as a group', () => {
    const group = el().querySelector('[role="group"]') as HTMLElement;
    const name = el().querySelector('#' + group.getAttribute('aria-labelledby'));
    expect(name?.textContent).toBe('Ramos');
  });
});
