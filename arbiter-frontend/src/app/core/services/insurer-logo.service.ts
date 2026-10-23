import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { Observable, map, tap } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthSessionService } from '../auth/auth-session.service';
import { silentRequest } from '../cache/query-cache';

/** The logo on screen and what the Marca section says about it. */
export interface InsurerLogo {
  /** Object URL of the image. */
  url: string;
  /** MIME type, e.g. `image/svg+xml`. */
  type: string;
  bytes: number;
  /** When it was uploaded (ISO). */
  uploadedAt: string;
}

/**
 * The logo of the session's insurer, as an object URL the app bar can show. Fetched as a blob, not
 * by `<img src>`: the endpoint needs the JWT, which an image request can't carry. Re-fetched only
 * when the session's logo version changes; nothing is kept once the session ends.
 */
@Injectable({ providedIn: 'root' })
export class InsurerLogoService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(AuthSessionService);
  private readonly url = `${environment.apiBaseUrl}/auth/insurer/logo`;

  private readonly _logo = signal<InsurerLogo | null>(null);
  readonly logo = this._logo.asReadonly();
  readonly logoUrl = computed(() => this._logo()?.url ?? null);
  private loadedVersion: string | null = null;

  constructor() {
    effect(() => {
      const version = this.session.session()?.insurerLogoVersion ?? null;
      untracked(() => this.sync(version));
    });
  }

  upload(file: File): Observable<void> {
    const body = new FormData();
    body.append('file', file);
    return this.http.put<{ version: string }>(this.url, body).pipe(
      tap(({ version }) => {
        // The picked file is the logo: show it as is instead of downloading it back.
        this.show(file, version);
        this.session.patch({ insurerLogoVersion: version });
      }),
      map(() => undefined),
    );
  }

  remove(): Observable<void> {
    return this.http.delete<void>(this.url).pipe(
      tap(() => {
        this.show(null, null);
        this.session.patch({ insurerLogoVersion: null });
      }),
    );
  }

  private sync(version: string | null): void {
    if (version === this.loadedVersion) return;
    if (version === null) {
      this.show(null, null);
      return;
    }
    this.loadedVersion = version;
    this.http.get(this.url, { responseType: 'blob', context: silentRequest() }).subscribe({
      next: (blob) => {
        // A newer version may have arrived while this one was downloading.
        if (this.loadedVersion === version) this.show(blob, version);
      },
      // No logo after all (removed from another session): the monogram stays.
      error: () => this.show(null, null),
    });
  }

  private show(image: Blob | null, version: string | null): void {
    const previous = this._logo();
    if (previous) URL.revokeObjectURL(previous.url);
    this._logo.set(
      image && version
        ? {
            url: URL.createObjectURL(image),
            type: image.type,
            bytes: image.size,
            uploadedAt: version,
          }
        : null,
    );
    this.loadedVersion = version;
  }
}
