import { TestBed, fakeAsync, tick } from '@angular/core/testing';

import { TOAST_EXIT_MS, ToastService } from './toast.service';

describe('ToastService', () => {
  let service: ToastService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ToastService);
  });

  it('keeps the description and the action', () => {
    const run = jasmine.createSpy('run');
    service.show('Paso completo', 'ok', 1000, {
      description: 'Elegí las coberturas.',
      action: { label: 'Siguiente', run },
    });

    const [toast] = service.toasts();
    expect(toast.description).toBe('Elegí las coberturas.');
    expect(toast.action?.label).toBe('Siguiente');
  });

  it('animates out before leaving the queue', fakeAsync(() => {
    service.show('Guardado', 'ok', 1000);

    tick(1000);
    expect(service.toasts()[0].leaving).toBeTrue();

    tick(TOAST_EXIT_MS);
    expect(service.toasts()).toEqual([]);
  }));

  it('stops counting while paused and resumes where it was', fakeAsync(() => {
    service.show('Guardado', 'ok', 1000);
    tick(600);

    service.pause();
    tick(5000);
    expect(service.toasts()[0].leaving).toBeFalse();

    service.resume();
    tick(399);
    expect(service.toasts()[0].leaving).toBeFalse();
    tick(1);
    expect(service.toasts()[0].leaving).toBeTrue();
    tick(TOAST_EXIT_MS);
  }));

  it('starts a toast that arrives while paused only on resume', fakeAsync(() => {
    service.pause();
    service.show('Guardado', 'ok', 1000);
    tick(3000);
    expect(service.toasts().length).toBe(1);

    service.resume();
    tick(1000 + TOAST_EXIT_MS);
    expect(service.toasts()).toEqual([]);
  }));
});
