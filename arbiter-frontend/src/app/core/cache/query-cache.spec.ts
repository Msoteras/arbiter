import { HttpContext } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import { AuthSession, AuthSessionService } from '../auth/auth-session.service';
import { QueryCache, SILENT_RETRY, paramsKey } from './query-cache';

describe('QueryCache', () => {
  let cache: QueryCache;
  let session: AuthSessionService;

  const sessionOf = (id: number): AuthSession => ({
    token: 't',
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    id,
    email: `u${id}@x.com`,
    rol: 'REFERENTE_ASEGURADORA',
    nombre: 'N',
    apellido: 'A',
    insuredId: null,
    onboardingComplete: null,
  });

  /** Collects every emission of a read, synchronously. */
  function readAll<T>(key: string, value: T, options = {}): T[] {
    const seen: T[] = [];
    cache.read(key, () => of(value), options).subscribe((v) => seen.push(v));
    return seen;
  }

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({});
    session = TestBed.inject(AuthSessionService);
    session.start(sessionOf(1));
    cache = TestBed.inject(QueryCache);
    TestBed.tick();
  });

  afterEach(() => sessionStorage.clear());

  it('shows the cached value at once and then the fresh one when it changed', () => {
    readAll('k', [1]);

    expect(readAll('k', [1, 2])).toEqual([[1], [1, 2]]);
  });

  it('emits once when the refresh brings the same data', () => {
    readAll('k', { a: 1 });

    expect(readAll('k', { a: 1 })).toEqual([{ a: 1 }]);
  });

  it('keeps a refresh the screen did not want and flags it as newer', () => {
    readAll('k', [1]);

    expect(readAll('k', [2], { apply: () => false })).toEqual([[1]]);
    expect(cache.hasNewer('k')).toBeTrue();
    expect(cache.peek('k')).toEqual([2]);

    expect(readAll('k', [2], { apply: () => false })).toEqual([[2]]);
    expect(cache.hasNewer('k')).toBeFalse();
  });

  it('keeps showing the cached value when the refresh fails', () => {
    readAll('k', [1]);
    let failed = false;
    const seen: unknown[] = [];

    cache
      .read('k', () => throwError(() => new Error('down')))
      .subscribe({ next: (v) => seen.push(v), error: () => (failed = true) });

    expect(seen).toEqual([[1]]);
    expect(failed).toBeFalse();
  });

  it('fails like a plain request when there is nothing cached', () => {
    let failed = false;
    cache
      .read('k', () => throwError(() => new Error('down')))
      .subscribe({ error: () => (failed = true) });

    expect(failed).toBeTrue();
  });

  it('asks once for two screens that read the same key at the same time', () => {
    const response = new Subject<number>();
    let requests = 0;
    const fetch = () => {
      requests++;
      return response;
    };

    cache.read('k', fetch).subscribe();
    cache.read('k', fetch).subscribe();
    response.next(1);
    response.complete();

    expect(requests).toBe(1);
  });

  it('drops every key under a prefix and asks again for the ones read lately', () => {
    let calls = 0;
    cache.read('cases:list:a', () => of(++calls)).subscribe();
    readAll('rules:fraud', 'f');

    cache.invalidate('cases:');

    expect(calls).toBe(2);
    expect(cache.peek('cases:list:a')).toBe(2);
    expect(cache.peek('rules:fraud')).toBe('f');
  });

  it('marks a prefetch so the retry skips the toast, and skips a key already cached', () => {
    const contexts: HttpContext[] = [];
    cache.prefetch('k', (context) => {
      contexts.push(context);
      return of(1);
    });
    cache.prefetch('k', (context) => {
      contexts.push(context);
      return of(2);
    });

    expect(contexts.length).toBe(1);
    expect(contexts[0].get(SILENT_RETRY)).toBeTrue();
    expect(cache.peek('k')).toBe(1);
  });

  it('empties when the session ends or another user logs in', () => {
    readAll('k', [1]);

    session.start(sessionOf(2));
    TestBed.tick();
    expect(cache.peek('k')).toBeUndefined();

    readAll('k', [1]);
    session.clear();
    TestBed.tick();
    expect(cache.peek('k')).toBeUndefined();
  });

  it('keeps the cache when the same user gets a new token', () => {
    readAll('k', [1]);

    session.start({ ...sessionOf(1), token: 'renewed' });
    TestBed.tick();

    expect(cache.peek('k')).toEqual([1]);
  });

  it('freezes what it stores, so a screen that mutates it fails while testing', () => {
    const [value] = readAll('k', { list: [1] });

    expect(() => (value.list as number[]).push(2)).toThrow();
  });

  it('builds the same key whatever the order and leaves out empty filters', () => {
    expect(paramsKey({ size: '10', page: '0', q: '' })).toBe(
      paramsKey({ page: '0', size: '10', status: undefined }),
    );
    expect(paramsKey({ status: ['A', 'B'] })).toBe('status=A,B');
  });
});
