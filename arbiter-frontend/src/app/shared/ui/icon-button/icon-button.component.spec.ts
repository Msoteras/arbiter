import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { IconButtonComponent, IconButtonIcon } from './icon-button.component';

@Component({
  imports: [IconButtonComponent],
  template: `
    <app-icon-button
      [icon]="icon()"
      label="Borrar Estudio Verifica"
      [disabled]="disabled()"
      (click)="clicks.set(clicks() + 1)"
    />
  `,
})
class Host {
  readonly icon = signal<IconButtonIcon>('delete');
  readonly disabled = signal(false);
  readonly clicks = signal(0);
}

describe('IconButtonComponent', () => {
  let fixture: ComponentFixture<Host>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
  });

  function button(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('button') as HTMLButtonElement;
  }

  /** The label is the only text the action has: it names it and shows on hover. */
  it('uses the label as accessible name and tooltip', () => {
    expect(button().getAttribute('aria-label')).toBe('Borrar Estudio Verifica');
    expect(button().getAttribute('title')).toBe('Borrar Estudio Verifica');
  });

  it('hides the drawing from assistive technology', () => {
    expect(button().querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('shows the delete action in the danger tone and the others without it', () => {
    expect(button().classList).toContain('danger');

    fixture.componentInstance.icon.set('edit');
    fixture.detectChanges();

    expect(button().classList).not.toContain('danger');
  });

  it('draws a different icon for each action', () => {
    const drawings = (['edit', 'delete', 'confirm'] as const).map((icon) => {
      fixture.componentInstance.icon.set(icon);
      fixture.detectChanges();
      return button().querySelector('path')?.getAttribute('d');
    });

    expect(new Set(drawings).size).toBe(3);
  });

  it('reaches the consumer when clicked', () => {
    button().click();

    expect(fixture.componentInstance.clicks()).toBe(1);
  });

  /** The consumer listens on the host, so the host itself has to stop taking pointer events. */
  it('disables the button and the host when disabled', () => {
    fixture.componentInstance.disabled.set(true);
    fixture.detectChanges();

    expect(button().disabled).toBeTrue();
    expect(fixture.nativeElement.querySelector('app-icon-button').classList).toContain(
      'is-disabled',
    );
  });
});
