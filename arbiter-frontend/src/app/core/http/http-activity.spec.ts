import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { silentRequest } from '../cache/query-cache';
import { HttpActivityService, httpActivityInterceptor } from './http-activity';

describe('httpActivityInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  let activity: HttpActivityService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([httpActivityInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
    activity = TestBed.inject(HttpActivityService);
  });

  afterEach(() => controller.verify());

  it('stays active until the last request ends', () => {
    http.get('/a').subscribe();
    http.get('/b').subscribe();
    expect(activity.active()).toBeTrue();

    controller.expectOne('/a').flush({});
    expect(activity.active()).toBeTrue();

    controller.expectOne('/b').flush({});
    expect(activity.active()).toBeFalse();
  });

  it('ends on an error too', () => {
    http.get('/a').subscribe({ error: () => undefined });
    controller.expectOne('/a').flush(null, { status: 500, statusText: 'Error' });
    expect(activity.active()).toBeFalse();
  });

  it('ignores the requests nobody is waiting for', () => {
    http.get('/poll', { context: silentRequest() }).subscribe();
    expect(activity.active()).toBeFalse();
    controller.expectOne('/poll').flush({});
  });
});
