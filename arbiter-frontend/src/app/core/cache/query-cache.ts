import { HttpContext, HttpContextToken } from '@angular/common/http';
import { Injectable, effect, inject, isDevMode, signal } from '@angular/core';
import { AsyncSubject, Observable } from 'rxjs';

import { AuthSessionService } from '../auth/auth-session.service';

/**
 * Marks a request nobody is waiting for (a prefetch, a poll): retried like any other, without the
 * toast and without the activity bar.
 */
export const SILENT_RETRY = new HttpContextToken<boolean>(() => false);

/** Builds the request; the cache passes the context, which a prefetch marks with SILENT_RETRY. */
export type CacheFetch<T> = (context: HttpContext) => Observable<T>;

export interface CacheReadOptions {
  /**
   * Whether a refreshed value may replace what the screen shows now. When it says no, the value
   * still goes into the cache and `hasNewer` turns on: an editable screen with unsaved changes, or
   * a list that waits for "Actualizar".
   */
  apply?: () => boolean;
}

interface Entry {
  has: boolean;
  value?: unknown;
  fetch: CacheFetch<unknown>;
  inflight?: AsyncSubject<unknown>;
  lastRead: number;
}

/** An invalidated key read this recently is fetched again right away, so the next visit is warm. */
const WARM_WINDOW_MS = 5 * 60_000;

/**
 * In-memory cache of the screens' reads, so going back to a screen shows its data at once and
 * refreshes it behind. Only in memory: a reload starts empty, and nothing personal is kept in the
 * browser. Writes invalidate what they affect (`cacheInvalidationInterceptor`), and the cache
 * empties when the session ends or changes user. See "Cache del frontend" in CLAUDE.md.
 */
@Injectable({ providedIn: 'root' })
export class QueryCache {
  private readonly entries = new Map<string, Entry>();
  private readonly newerKeys = signal<ReadonlySet<string>>(new Set());

  constructor() {
    const session = inject(AuthSessionService);
    let userId = session.session()?.id ?? null;
    effect(() => {
      const current = session.session()?.id ?? null;
      if (current !== userId) {
        userId = current;
        this.clear();
      }
    });
  }

  /**
   * The cached value right away, if there is one, then the fresh one if it changed. Without a cached
   * value it is a plain request. A failed refresh keeps the cached value and does not error.
   */
  read<T>(key: string, fetch: CacheFetch<T>, options: CacheReadOptions = {}): Observable<T> {
    return new Observable<T>((subscriber) => {
      const entry = this.entry(key, fetch as CacheFetch<unknown>);
      entry.lastRead = Date.now();
      const hadValue = entry.has;
      const shown = entry.value;
      if (hadValue) {
        this.markSeen(key);
        subscriber.next(shown as T);
      }
      const subscription = this.refresh(entry, new HttpContext()).subscribe({
        next: (fresh) => {
          if (!hadValue) {
            subscriber.next(fresh as T);
            return;
          }
          if (sameValue(fresh, shown)) {
            return;
          }
          if (options.apply?.() ?? true) {
            this.markSeen(key);
            subscriber.next(fresh as T);
          } else {
            this.markNewer(key);
          }
        },
        error: (err) => (hadValue ? subscriber.complete() : subscriber.error(err)),
        complete: () => subscriber.complete(),
      });
      return () => subscription.unsubscribe();
    });
  }

  /** Fills a key nobody is looking at yet. Silent: no toast, and a failure is dropped. */
  prefetch<T>(key: string, fetch: CacheFetch<T>): void {
    const entry = this.entry(key, fetch as CacheFetch<unknown>);
    if (entry.has || entry.inflight) {
      return;
    }
    this.refresh(entry, silentRequest()).subscribe({ error: () => undefined });
  }

  peek<T>(key: string): T | undefined {
    const entry = this.entries.get(key);
    return entry?.has ? (entry.value as T) : undefined;
  }

  /** Whether the cache holds a newer value than the one the screen chose to keep. */
  hasNewer(key: string): boolean {
    return this.newerKeys().has(key);
  }

  /**
   * Drops every key that starts with `prefix`. The ones read lately are fetched again at once, so
   * nobody sees what they just changed in its old state.
   */
  invalidate(prefix: string): void {
    for (const [key, entry] of [...this.entries]) {
      if (!key.startsWith(prefix)) {
        continue;
      }
      const fresh: Entry = { has: false, fetch: entry.fetch, lastRead: entry.lastRead };
      this.entries.set(key, fresh);
      this.markSeen(key);
      if (Date.now() - entry.lastRead < WARM_WINDOW_MS) {
        this.refresh(fresh, silentRequest()).subscribe({ error: () => undefined });
      }
    }
  }

  clear(): void {
    this.entries.clear();
    this.newerKeys.set(new Set());
  }

  private entry(key: string, fetch: CacheFetch<unknown>): Entry {
    let entry = this.entries.get(key);
    if (!entry) {
      entry = { has: false, fetch, lastRead: 0 };
      this.entries.set(key, entry);
    }
    // The latest one: an invalidation asks again with what the screen asked with last.
    entry.fetch = fetch;
    return entry;
  }

  /**
   * One request per key at a time, and it runs to the end even if the screen that asked for it is
   * gone: the answer is still worth keeping.
   */
  private refresh(entry: Entry, context: HttpContext): Observable<unknown> {
    if (entry.inflight) {
      return entry.inflight;
    }
    const result = new AsyncSubject<unknown>();
    entry.inflight = result;
    entry.fetch(context).subscribe({
      next: (value) => {
        entry.value = isDevMode() ? deepFreeze(value) : value;
        entry.has = true;
        result.next(entry.value);
      },
      error: (err) => {
        entry.inflight = undefined;
        result.error(err);
      },
      complete: () => {
        entry.inflight = undefined;
        result.complete();
      },
    });
    // `result`, not `entry.inflight`: a synchronous response has already cleared it.
    return result;
  }

  private markNewer(key: string): void {
    this.newerKeys.update((keys) => new Set(keys).add(key));
  }

  private markSeen(key: string): void {
    if (this.newerKeys().has(key)) {
      this.newerKeys.update((keys) => {
        const next = new Set(keys);
        next.delete(key);
        return next;
      });
    }
  }
}

export function silentRequest(): HttpContext {
  return new HttpContext().set(SILENT_RETRY, true);
}

/** The responses are plain JSON, so comparing their serialization is exact. */
function sameValue(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/**
 * Development only: a screen that mutates what it received would be changing the cached copy that
 * the next visit shows. Frozen, the mutation throws while testing instead.
 */
function deepFreeze<T>(value: T): T {
  if (value !== null && typeof value === 'object' && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value as Record<string, unknown>)) {
      deepFreeze(child);
    }
  }
  return value;
}

/** Stable key for a set of query params: the same filters always map to the same key. */
export function paramsKey(params: object): string {
  return Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '' && v !== false)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${k}=${Array.isArray(v) ? v.join(',') : String(v)}`)
    .join('&');
}
