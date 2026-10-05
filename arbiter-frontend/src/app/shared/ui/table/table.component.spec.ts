import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { TableComponent } from './table.component';

@Component({
  imports: [TableComponent],
  template: `
    <div [style.width.px]="width">
      <app-table [pinFirstColumn]="true" [stickyHeader]="true" scrollLabel="Tabla de prueba">
        <thead>
          <tr>
            <th style="min-width: 400px">Nº</th>
            <th style="min-width: 400px">Asegurado</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td style="min-width: 400px">#1</td>
            <td style="min-width: 400px">Ana Pérez</td>
          </tr>
        </tbody>
      </app-table>
    </div>
  `,
})
class HostComponent {
  width = 200;
}

describe('TableComponent', () => {
  let fixture: ComponentFixture<HostComponent>;

  function table(): HTMLElement {
    return (fixture.nativeElement as HTMLElement).querySelector('app-table')!;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  });

  it('pins the first column and the header when asked to', () => {
    const inner = table().querySelector('table')!;

    expect(inner.classList).toContain('pinned');
    expect(inner.classList).toContain('sticky-head');
    expect(getComputedStyle(inner.querySelector('tbody td')!).position).toBe('sticky');
  });

  /** A scrollable region that can't be reached by keyboard is a chunk of the page nobody can read. */
  it('becomes a keyboard-reachable region only while it actually scrolls', async () => {
    await fixture.whenStable();
    fixture.detectChanges();

    expect(table().getAttribute('tabindex')).toBe('0');
    expect(table().getAttribute('role')).toBe('region');
    expect(table().getAttribute('aria-label')).toBe('Tabla de prueba');

    fixture.componentInstance.width = 2000;
    fixture.detectChanges();
    table().dispatchEvent(new Event('scroll'));
    fixture.detectChanges();

    expect(table().getAttribute('tabindex')).toBeNull();
    expect(table().getAttribute('role')).toBeNull();
  });

  /** The divider only earns its line once something is hidden behind the pinned column. */
  it('marks itself as scrolled once it is scrolled sideways', async () => {
    await fixture.whenStable();
    const host = table();

    expect(host.querySelector('table')!.classList).not.toContain('scrolled');

    host.scrollLeft = 50;
    host.dispatchEvent(new Event('scroll'));
    fixture.detectChanges();

    expect(host.querySelector('table')!.classList).toContain('scrolled');
  });
});
