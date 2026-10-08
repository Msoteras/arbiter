import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import {
  HttpClient,
  HttpErrorResponse,
  HttpHeaders,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ToastService } from '../../shared/ui/toast/toast.service';
import {
  RETRY_DELAYS_MS,
  UPSTREAM_UNREACHABLE_HEADER,
  upstreamRetryInterceptor,
} from './upstream-retry.interceptor';

describe('upstreamRetryInterceptor', () => {
  const url = '/api/v1/auth/login';
  const unreachable = {
    status: 503,
    statusText: 'Service Unavailable',
    headers: new HttpHeaders({ [UPSTREAM_UNREACHABLE_HEADER]: 'true' }),
  };

  let http: HttpClient;
  let controller: HttpTestingController;
  let toastShow: jasmine.Spy;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([upstreamRetryInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
    toastShow = spyOn(TestBed.inject(ToastService), 'show');
  });

  afterEach(() => controller.verify());

  it('retries a request that never reached the backend until it answers', fakeAsync(() => {
    let body: unknown;
    http.post(url, { email: 'a@b.c' }).subscribe((b) => (body = b));

    controller.expectOne(url).flush(null, unreachable);
    tick(RETRY_DELAYS_MS[0]);
    controller.expectOne(url).flush(null, unreachable);
    tick(RETRY_DELAYS_MS[1]);
    controller.expectOne(url).flush({ token: 'x' });

    expect(body).toEqual({ token: 'x' });
  }));

  it('gives up after the last delay and surfaces the error', fakeAsync(() => {
    let error: HttpErrorResponse | undefined;
    http.get(url).subscribe({ error: (e: HttpErrorResponse) => (error = e) });

    controller.expectOne(url).flush(null, unreachable);
    for (const delay of RETRY_DELAYS_MS) {
      tick(delay);
      controller.expectOne(url).flush(null, unreachable);
    }

    expect(error?.status).toBe(503);
  }));

  it('does not retry a 503 the backend itself returned', fakeAsync(() => {
    let error: HttpErrorResponse | undefined;
    http.post(url, {}).subscribe({ error: (e: HttpErrorResponse) => (error = e) });

    controller.expectOne(url).flush(null, { status: 503, statusText: 'Service Unavailable' });
    tick(RETRY_DELAYS_MS[0]);

    expect(error?.status).toBe(503);
    expect(toastShow).not.toHaveBeenCalled();
  }));

  it('does not retry a 502: the request may have reached the backend', fakeAsync(() => {
    let error: HttpErrorResponse | undefined;
    http.post(url, {}).subscribe({ error: (e: HttpErrorResponse) => (error = e) });

    controller.expectOne(url).flush(null, { status: 502, statusText: 'Bad Gateway' });
    tick(RETRY_DELAYS_MS[0]);

    expect(error?.status).toBe(502);
  }));

  it('shows the notice once for requests that wait at the same time', fakeAsync(() => {
    http.get('/api/v1/cases').subscribe();
    http.get('/api/v1/reports/metrics').subscribe();

    controller.expectOne('/api/v1/cases').flush(null, unreachable);
    controller.expectOne('/api/v1/reports/metrics').flush(null, unreachable);
    tick(RETRY_DELAYS_MS[0]);
    controller.expectOne('/api/v1/cases').flush([]);
    controller.expectOne('/api/v1/reports/metrics').flush({});

    expect(toastShow).toHaveBeenCalledTimes(1);
  }));
});
