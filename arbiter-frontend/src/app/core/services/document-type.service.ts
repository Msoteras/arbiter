import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Observable, catchError, map, of, retry, shareReplay } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  DocumentTypeDef,
  NON_AGENDA_DOCUMENT_TYPES,
  withDocumentLabels,
} from '../models/business-rules';

/** Mirrors common-lib's DocumentTypeResponse. */
interface DocumentTypeResponse {
  code: string;
  name: string;
}

const toDefs = (types: DocumentTypeResponse[]): DocumentTypeDef[] =>
  types.map(({ code, name }) => ({ code, label: name }));

/**
 * The document catalog from `arbiter_common.document_type`, the one place documents are named: the
 * insured, the analyst and the fraud report read the same name. The same for every insurer, so it
 * is fetched once per session.
 */
@Injectable({ providedIn: 'root' })
export class DocumentTypeService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/document-types`;

  // shareReplay resets on error, so a failed fetch isn't replayed: the next caller asks again.
  private readonly catalog$ = this.http
    .get<DocumentTypeResponse[]>(this.base)
    .pipe(
      retry({ count: 2, delay: 1000 }),
      map(toDefs),
      shareReplay({ bufferSize: 1, refCount: false }),
    );

  /** Empty until it arrives, or if it never does: names then fall back to the code. */
  private readonly catalog = toSignal(this.catalog$.pipe(catchError(() => of([]))), {
    initialValue: [] as DocumentTypeDef[],
  });

  /** The catalog plus the providers' reports, which no schedule asks for but a case can carry. */
  private readonly named = computed<readonly DocumentTypeDef[]>(() => [
    ...this.catalog(),
    ...NON_AGENDA_DOCUMENT_TYPES,
  ]);

  /** Every document, for the screens that offer the whole catalog when no schedule applies. */
  all(): Observable<readonly DocumentTypeDef[]> {
    return this.catalog$.pipe(catchError(() => of([])));
  }

  /** What one branch's schedule and Fast Track lists may ask for. */
  forBranch(branchId: number): Observable<readonly DocumentTypeDef[]> {
    return this.http
      .get<DocumentTypeResponse[]>(this.base, { params: { branchId: String(branchId) } })
      .pipe(map(toDefs));
  }

  /** Reads the catalog signal, so a `computed` calling it updates once the catalog arrives. */
  label(code: string): string {
    return this.named().find((d) => d.code === code)?.label ?? code;
  }

  /** Backend reasons embed document codes ("…: police_report"); swaps them for their names. */
  withLabels(text: string): string {
    return withDocumentLabels(text, this.named());
  }

  /** For pure functions that take the names as an argument. */
  definitions(): readonly DocumentTypeDef[] {
    return this.named();
  }
}
