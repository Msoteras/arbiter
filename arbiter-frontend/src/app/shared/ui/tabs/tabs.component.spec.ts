import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { TabOption, TabsComponent } from './tabs.component';

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

  it('drops the sliding underline once the tabs wrap to a second row', async () => {
    const host = fixture.nativeElement as HTMLElement;
    const ink = (): HTMLElement => host.querySelector('.ink') as HTMLElement;
    await fixture.whenStable();

    expect(ink().classList).toContain('placed');

    host.style.display = 'block';
    host.style.width = '120px';
    for (let frame = 0; frame < 2; frame++) {
      await new Promise((resolve) => requestAnimationFrame(resolve));
    }

    expect(ink().classList).not.toContain('placed');
    expect(tabs()[0].classList).toContain('active');
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

  describe('with counts, dots and projected content', () => {
    @Component({
      imports: [TabsComponent],
      template: `
        <app-tabs [tabs]="tabs" [(value)]="value" [scrollable]="true">
          <button type="button" class="extra">Sin asignar</button>
        </app-tabs>
      `,
    })
    class RichHost {
      readonly tabs: TabOption[] = [
        { id: 'analysis', label: 'Análisis', count: 3 },
        { id: 'images', label: 'Imágenes', status: 'notice', statusLabel: 'con coincidencias' },
        { id: 'empty', label: 'Vacía', count: 0, status: 'required' },
      ];
      readonly value = signal('analysis');
    }

    let rich: ComponentFixture<RichHost>;

    beforeEach(() => {
      rich = TestBed.createComponent(RichHost);
      rich.detectChanges();
    });

    function richTabs(): HTMLButtonElement[] {
      return [...rich.nativeElement.querySelectorAll('[role="tab"]')] as HTMLButtonElement[];
    }

    it('shows the count after the label, zero included', () => {
      expect(richTabs()[0].querySelector('.tab-count')?.textContent?.trim()).toBe('3');
      expect(richTabs()[1].querySelector('.tab-count')).toBeNull();
      expect(richTabs()[2].querySelector('.tab-count')?.textContent?.trim()).toBe('0');
    });

    it('names the dot for screen readers, with the default when no label is given', () => {
      expect(richTabs()[1].querySelector('.tab-dot.is-notice')).not.toBeNull();
      expect(richTabs()[1].querySelector('.sr-only')?.textContent).toBe('(con coincidencias)');
      expect(richTabs()[2].querySelector('.sr-only')?.textContent).toBe('(falta configurar)');
    });

    it('puts projected content in the strip but outside the tablist', () => {
      const extra = rich.nativeElement.querySelector('.extra') as HTMLElement;

      expect(extra.closest('.strip')).not.toBeNull();
      expect(extra.closest('[role="tablist"]')).toBeNull();
    });

    it('wraps from the last tab to the first, skipping the projected content', () => {
      rich.componentInstance.value.set('empty');
      rich.detectChanges();
      richTabs()[2].dispatchEvent(
        new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }),
      );
      rich.detectChanges();

      expect(rich.componentInstance.value()).toBe('analysis');
      expect(document.activeElement).toBe(richTabs()[0]);
    });

    it('scrolls instead of wrapping when asked', () => {
      expect(rich.nativeElement.querySelector('.strip').classList).toContain('is-scrollable');
    });
  });
});
