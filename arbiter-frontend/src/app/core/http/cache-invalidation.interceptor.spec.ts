import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { QueryCache } from '../cache/query-cache';
import { cacheInvalidationInterceptor } from './cache-invalidation.interceptor';

describe('cacheInvalidationInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  let invalidate: jasmine.Spy;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([cacheInvalidationInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
    invalidate = spyOn(TestBed.inject(QueryCache), 'invalidate');
  });

  afterEach(() => controller.verify());

  it('a decision on a case makes every case list and count stale', () => {
    http.post('/api/v1/cases/7/decision', {}).subscribe();
    controller.expectOne('/api/v1/cases/7/decision').flush({});

    expect(invalidate).toHaveBeenCalledWith('cases:');
  });

  it('a chat message leaves the case lists alone', () => {
    http.post('/api/v1/cases/7/messages', {}).subscribe();
    controller.expectOne('/api/v1/cases/7/messages').flush({});

    expect(invalidate).not.toHaveBeenCalled();
  });

  it('renaming a branch refreshes the branches and the coverage counts', () => {
    http.put('/api/v1/rules/branches/1', {}).subscribe();
    controller.expectOne('/api/v1/rules/branches/1').flush({});

    expect(invalidate.calls.allArgs()).toEqual([['rules:branches'], ['rules:coverage-summary']]);
  });

  it('a failed write invalidates nothing', () => {
    http.put('/api/v1/rules/scoring', {}).subscribe({ error: () => undefined });
    controller.expectOne('/api/v1/rules/scoring').flush(null, { status: 422, statusText: 'x' });

    expect(invalidate).not.toHaveBeenCalled();
  });

  it('reads never invalidate', () => {
    http.get('/api/v1/cases').subscribe();
    controller.expectOne('/api/v1/cases').flush({});

    expect(invalidate).not.toHaveBeenCalled();
  });
});
