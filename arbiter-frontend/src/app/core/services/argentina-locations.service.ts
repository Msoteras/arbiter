import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of, shareReplay } from 'rxjs';

/** `{ "Buenos Aires": ["Adrogué", ...], ... }`. */
type LocalitiesByProvince = Record<string, string[]>;

/**
 * Argentine provinces and localities from the official Georef dataset, shipped as a static asset
 * (not insurer-specific, so no backend round trip). It is the only source for
 * `cases.province`/`cases.locality`, which keeps those columns groupable.
 */
@Injectable({ providedIn: 'root' })
export class ArgentinaLocationsService {
  private readonly http = inject(HttpClient);

  private readonly data$ = this.http
    // Absolute path: XHR resolves relative URLs against the current route, not <base href>.
    .get<LocalitiesByProvince>('/data/ar-localities.json')
    .pipe(shareReplay({ bufferSize: 1, refCount: false }));

  /** Already sorted alphabetically in the dataset. */
  provinces(): Observable<string[]> {
    return this.data$.pipe(map((byProvince) => Object.keys(byProvince)));
  }

  localities(province: string): Observable<string[]> {
    if (!province) {
      return of([]);
    }
    return this.data$.pipe(map((byProvince) => byProvince[province] ?? []));
  }
}
