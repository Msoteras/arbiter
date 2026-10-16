import { HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { tap } from 'rxjs';

import { QueryCache } from '../cache/query-cache';

/**
 * What each write makes stale, by URL. A write that matches several rules invalidates all of them.
 * Coarse on purpose: any write on a case touches lists, counts and summaries at once, and a
 * missed key would show the user their own change undone.
 */
const INVALIDATIONS: { matches: (url: string) => boolean; prefixes: string[] }[] = [
  { matches: (url) => url.includes('/auth/users'), prefixes: ['users:'] },
  { matches: (url) => url.includes('/auth/profile'), prefixes: ['insured:profile'] },
  {
    // Chat messages and read marks change only the message inbox, which has its own store.
    matches: (url) => url.includes('/cases') && !url.includes('/messages'),
    prefixes: ['cases:'],
  },
  {
    // Authorities and providers list every branch by name, so a new or renamed one reaches them too.
    matches: (url) => url.includes('/rules/branches'),
    prefixes: ['rules:branches', 'rules:coverage-summary', 'rules:authorities', 'rules:providers'],
  },
  { matches: (url) => url.includes('/coverages'), prefixes: ['rules:coverage-summary'] },
  { matches: (url) => url.includes('/expert-firms'), prefixes: ['rules:providers'] },
  { matches: (url) => url.includes('/settlement-authorities'), prefixes: ['rules:authorities'] },
  { matches: (url) => url.includes('/rules/fraud-record-rule'), prefixes: ['rules:fraud'] },
  { matches: (url) => url.includes('/rules/scoring'), prefixes: ['rules:scoring'] },
  { matches: (url) => url.includes('/rules/resolution-target'), prefixes: ['rules:target'] },
];

/** After a successful write, drops what it made stale (`QueryCache.invalidate`). */
export const cacheInvalidationInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.method === 'GET') {
    return next(req);
  }
  const prefixes = INVALIDATIONS.filter((rule) => rule.matches(req.url)).flatMap((r) => r.prefixes);
  if (prefixes.length === 0) {
    return next(req);
  }
  const cache = inject(QueryCache);
  return next(req).pipe(
    tap((event) => {
      if (event instanceof HttpResponse) {
        prefixes.forEach((prefix) => cache.invalidate(prefix));
      }
    }),
  );
};
