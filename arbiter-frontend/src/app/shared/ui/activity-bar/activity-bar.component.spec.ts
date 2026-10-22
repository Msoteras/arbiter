import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';

import { ActivityBarComponent } from './activity-bar.component';

describe('ActivityBarComponent', () => {
  let fixture: ComponentFixture<ActivityBarComponent>;

  const bar = () => fixture.nativeElement.querySelector('.bar') as HTMLElement;
  const scale = () => Number(/scaleX\(([\d.]+)\)/.exec(bar().style.transform)?.[1]);
  const set = (active: boolean) => {
    fixture.componentRef.setInput('active', active);
    fixture.detectChanges();
  };

  beforeEach(() => {
    fixture = TestBed.createComponent(ActivityBarComponent);
    set(false);
  });

  it('stays hidden for a request shorter than the delay', fakeAsync(() => {
    set(true);
    tick(100);
    set(false);
    tick(1000);
    fixture.detectChanges();
    expect(bar().classList).toContain('idle');
    expect(scale()).toBe(0);
  }));

  it('creeps forward without reaching the end while the work lasts', fakeAsync(() => {
    set(true);
    tick(200);
    fixture.detectChanges();
    const first = scale();
    tick(3000);
    fixture.detectChanges();
    expect(scale()).toBeGreaterThan(first);
    expect(scale()).toBeLessThan(1);
    set(false);
    tick(1000);
  }));

  it('fills up from where it is and then resets out of sight', fakeAsync(() => {
    set(true);
    tick(1000);
    set(false);
    expect(bar().classList).toContain('done');
    expect(scale()).toBe(1);
    tick(600);
    fixture.detectChanges();
    expect(bar().classList).toContain('idle');
    expect(scale()).toBe(0);
  }));
});
