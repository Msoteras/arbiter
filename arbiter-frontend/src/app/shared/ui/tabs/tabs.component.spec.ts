import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { TabsComponent } from './tabs.component';

@Component({
  imports: [TabsComponent],
  template: `<app-tabs [tabs]="tabs" [(value)]="value" ariaLabel="Secciones" />`,
})
class Host {
  readonly tabs = [
    { id: 'coverages', label: 'Coberturas' },
    { id: 'fastTrack', label: 'Fast Track' },
    { id: 'documents', label: 'Documentación' },
  ];
  readonly value = signal('coverages');
}

describe('TabsComponent', () => {
  let fixture: ComponentFixture<Host>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
  });

  function tabs(): HTMLButtonElement[] {
    return [...fixture.nativeElement.querySelectorAll('[role="tab"]')] as HTMLButtonElement[];
  }

  function press(tab: HTMLButtonElement, key: string): void {
    tab.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
    fixture.detectChanges();
  }

  it('marks the selected tab for assistive technology', () => {
    expect(tabs().map((t) => t.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false']);
    expect(fixture.nativeElement.querySelector('[role="tablist"]').getAttribute('aria-label')).toBe(
      'Secciones',
    );
  });

  it('selects the clicked tab', () => {
    tabs()[1].click();
    fixture.detectChanges();

    expect(fixture.componentInstance.value()).toBe('fastTrack');
    expect(tabs()[1].classList).toContain('active');
  });

  it('leaves only the selected tab in the tab order', () => {
    expect(tabs().map((t) => t.tabIndex)).toEqual([0, -1, -1]);
  });

  it('moves the selection and the focus with the arrow keys', () => {
    press(tabs()[0], 'ArrowRight');

    expect(fixture.componentInstance.value()).toBe('fastTrack');
    expect(document.activeElement).toBe(tabs()[1]);

    press(tabs()[1], 'ArrowLeft');

    expect(fixture.componentInstance.value()).toBe('coverages');
  });

  it('wraps around at both ends', () => {
    press(tabs()[0], 'ArrowLeft');
    expect(fixture.componentInstance.value()).toBe('documents');

    press(tabs()[2], 'ArrowRight');
    expect(fixture.componentInstance.value()).toBe('coverages');
  });

  it('jumps to the first and last tab with Home and End', () => {
    press(tabs()[0], 'End');
    expect(fixture.componentInstance.value()).toBe('documents');

    press(tabs()[2], 'Home');
    expect(fixture.componentInstance.value()).toBe('coverages');
  });

  it('ignores any other key', () => {
    press(tabs()[0], 'a');

    expect(fixture.componentInstance.value()).toBe('coverages');
  });
});
