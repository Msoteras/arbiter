import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { TabNavComponent } from './tab-nav.component';

@Component({ template: '' })
class Blank {}

@Component({
  imports: [TabNavComponent],
  template: `<app-tab-nav
    [tabs]="tabs"
    [queryParams]="{ from: '2026-10-01' }"
    ariaLabel="Reporte"
  />`,
})
class Host {
  readonly tabs = [
    { path: '/resolutions', label: 'Resolución de siniestros' },
    { path: '/fraud', label: 'Detección de fraude' },
  ];
}

describe('TabNavComponent', () => {
  let fixture: ComponentFixture<Host>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Host],
      providers: [
        provideRouter([
          { path: 'resolutions', component: Blank },
          { path: 'fraud', component: Blank },
        ]),
      ],
    }).compileComponents();
    await TestBed.inject(Router).navigateByUrl('/fraud?from=2026-10-01');
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  function links(): HTMLAnchorElement[] {
    return [...fixture.nativeElement.querySelectorAll('a.tab')] as HTMLAnchorElement[];
  }

  it('is a labelled navigation, not a tablist', () => {
    const nav = fixture.nativeElement.querySelector('nav');

    expect(nav.getAttribute('aria-label')).toBe('Reporte');
    expect(fixture.nativeElement.querySelector('[role="tablist"]')).toBeNull();
  });

  it('marks the current route as the active tab', () => {
    expect(links()[1].classList).toContain('active');
    expect(links()[1].getAttribute('aria-current')).toBe('page');
    expect(links()[0].getAttribute('aria-current')).toBeNull();
  });

  it('carries the query params to every tab', () => {
    expect(links()[0].getAttribute('href')).toBe('/resolutions?from=2026-10-01');
  });
});
