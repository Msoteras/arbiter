import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';

import { ButtonComponent } from './button.component';

@Component({
  imports: [ButtonComponent],
  template: `<app-button
    [saving]="saving()"
    [saveFailed]="failed()"
    (saveSettled)="settled = settled + 1"
    >Guardar</app-button
  >`,
})
class SaveHostComponent {
  readonly saving = signal(false);
  readonly failed = signal(false);
  settled = 0;
}

describe('ButtonComponent · save cycle', () => {
  let fixture: ComponentFixture<SaveHostComponent>;

  function shownLabel(): string {
    const labels = fixture.nativeElement.querySelectorAll(
      '.label:not(.off)',
    ) as NodeListOf<HTMLElement>;
    return labels[0].textContent!.trim();
  }

  function button(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('button') as HTMLButtonElement;
  }

  beforeEach(() => {
    fixture = TestBed.createComponent(SaveHostComponent);
    fixture.detectChanges();
  });

  it('goes Guardar → Guardando → Guardado → Guardar and then settles', fakeAsync(() => {
    expect(shownLabel()).toBe('Guardar');

    fixture.componentInstance.saving.set(true);
    fixture.detectChanges();
    expect(shownLabel()).toBe('Guardando');
    expect(button().classList).toContain('saving');
    expect(button().disabled).toBeTrue();

    fixture.componentInstance.saving.set(false);
    fixture.detectChanges();
    expect(shownLabel()).toBe('✓ Guardado');
    expect(fixture.componentInstance.settled).toBe(0);

    tick(1600);
    fixture.detectChanges();
    expect(shownLabel()).toBe('Guardar');
    expect(fixture.componentInstance.settled).toBe(1);
  }));

  it('a failed save goes straight back to Guardar', fakeAsync(() => {
    fixture.componentInstance.saving.set(true);
    fixture.detectChanges();
    fixture.componentInstance.failed.set(true);
    fixture.componentInstance.saving.set(false);
    fixture.detectChanges();
    expect(shownLabel()).toBe('Guardar');
    tick(1600);
    expect(fixture.componentInstance.settled).toBe(0);
  }));
});
