import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DatePickerComponent, DateShortcut } from './date-picker.component';
import { DateRangePickerComponent } from './date-range-picker.component';
import { TimePickerComponent, TimeSlot } from './time-picker.component';

function click(fixture: ComponentFixture<unknown>, selector: string): void {
  (fixture.nativeElement.querySelector(selector) as HTMLElement).click();
  fixture.detectChanges();
}

function dayButton(fixture: ComponentFixture<unknown>, date: string): HTMLButtonElement {
  return fixture.nativeElement.querySelector(`[data-date="${date}"]`) as HTMLButtonElement;
}

function chip(fixture: ComponentFixture<unknown>, label: string): HTMLButtonElement {
  const chips = Array.from(
    fixture.nativeElement.querySelectorAll('.chip') as NodeListOf<HTMLButtonElement>,
  );
  return chips.find((c) => c.textContent?.trim() === label)!;
}

function triggerText(fixture: ComponentFixture<unknown>): string {
  return (fixture.nativeElement.querySelector('.trigger-label') as HTMLElement).textContent!.trim();
}

@Component({
  imports: [DatePickerComponent],
  template: `<app-date-picker
    [(value)]="date"
    [min]="min()"
    [max]="'2026-10-14'"
    [shortcuts]="shortcuts"
  />`,
})
class DateHostComponent {
  readonly date = signal('');
  readonly min = signal<string | null>(null);
  readonly shortcuts: DateShortcut[] = [
    { label: 'Hoy', date: '2026-10-14' },
    { label: 'Ayer', date: '2026-10-13' },
  ];
}

describe('DatePickerComponent', () => {
  let fixture: ComponentFixture<DateHostComponent>;

  beforeEach(() => {
    fixture = TestBed.createComponent(DateHostComponent);
    fixture.detectChanges();
  });

  it('opens the calendar with the shortcuts above it', () => {
    click(fixture, '.trigger');
    expect(chip(fixture, 'Hoy')).toBeTruthy();
    expect(dayButton(fixture, '2026-10-01')).toBeTruthy();
  });

  it('a shortcut sets the date, closes the panel and names the day in the field', () => {
    click(fixture, '.trigger');
    chip(fixture, 'Ayer').click();
    fixture.detectChanges();
    expect(fixture.componentInstance.date()).toBe('2026-10-13');
    expect(fixture.nativeElement.querySelector('.panel')).toBeNull();
    expect(triggerText(fixture)).toContain('Ayer');
  });

  it('days after max cannot be picked', () => {
    click(fixture, '.trigger');
    expect(dayButton(fixture, '2026-10-15').disabled).toBeTrue();
    expect(dayButton(fixture, '2026-10-14').disabled).toBeFalse();
  });

  it('leaves out the shortcuts outside the allowed range', () => {
    fixture.componentInstance.min.set('2026-10-14');
    fixture.detectChanges();
    click(fixture, '.trigger');
    expect(chip(fixture, 'Ayer')).toBeUndefined();
    expect(chip(fixture, 'Hoy')).toBeTruthy();
  });
});

@Component({
  imports: [TimePickerComponent],
  template: `<app-time-picker [(value)]="time" [slots]="slots" />`,
})
class TimeHostComponent {
  readonly time = signal('');
  readonly slots: TimeSlot[] = [
    { label: 'Mañana', time: '09:00', startsAt: '06:00' },
    { label: 'Tarde', time: '15:00', startsAt: '12:00' },
    { label: 'Noche', time: '21:00', startsAt: '19:00' },
  ];
}

describe('TimePickerComponent', () => {
  let fixture: ComponentFixture<TimeHostComponent>;

  beforeEach(() => {
    fixture = TestBed.createComponent(TimeHostComponent);
    fixture.detectChanges();
  });

  it('a slot suggests its representative hour and keeps the panel open', () => {
    click(fixture, '.trigger');
    chip(fixture, 'Tarde').click();
    fixture.detectChanges();
    expect(fixture.componentInstance.time()).toBe('15:00');
    expect(fixture.nativeElement.querySelector('.panel')).not.toBeNull();
  });

  it('an exact time shows the slot it falls in', () => {
    fixture.componentInstance.time.set('20:30');
    fixture.detectChanges();
    expect(triggerText(fixture)).toBe('20:30 · Noche');
  });
});

@Component({
  imports: [DateRangePickerComponent],
  template: `<app-date-range-picker [(from)]="from" [(to)]="to" [max]="'2026-10-14'" />`,
})
class RangeHostComponent {
  readonly from = signal('');
  readonly to = signal('');
}

describe('DateRangePickerComponent', () => {
  let fixture: ComponentFixture<RangeHostComponent>;

  beforeEach(() => {
    fixture = TestBed.createComponent(RangeHostComponent);
    fixture.detectChanges();
  });

  function pick(date: string): void {
    dayButton(fixture, date).click();
    fixture.detectChanges();
  }

  it('the first click sets "Desde" and keeps the panel open for "Hasta"', () => {
    click(fixture, '.trigger');
    pick('2026-10-05');
    expect(fixture.componentInstance.from()).toBe('2026-10-05');
    expect(fixture.nativeElement.querySelector('.panel')).not.toBeNull();
    pick('2026-10-10');
    expect(fixture.componentInstance.to()).toBe('2026-10-10');
    expect(fixture.nativeElement.querySelector('.panel')).toBeNull();
    expect(triggerText(fixture)).toBe('05/10/2026 – 10/10/2026');
  });

  it('only "Desde" leaves the range open', () => {
    click(fixture, '.trigger');
    pick('2026-10-05');
    click(fixture, '.panel-actions app-button:last-child button');
    expect(fixture.componentInstance.to()).toBe('');
    expect(triggerText(fixture)).toBe('Desde 05/10/2026');
  });

  it('only "Hasta" is possible too', () => {
    click(fixture, '.trigger');
    (fixture.nativeElement.querySelectorAll('.end-pick')[1] as HTMLElement).click();
    fixture.detectChanges();
    pick('2026-10-08');
    expect(fixture.componentInstance.from()).toBe('');
    expect(triggerText(fixture)).toBe('Hasta 08/10/2026');
  });

  it('a "Hasta" before "Desde" becomes the new start', () => {
    click(fixture, '.trigger');
    pick('2026-10-10');
    pick('2026-10-03');
    expect(fixture.componentInstance.from()).toBe('2026-10-03');
    expect(fixture.componentInstance.to()).toBe('');
  });
});
