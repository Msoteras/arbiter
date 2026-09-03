import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CaseMessage, CaseMessageThread } from '../../core/models/case-message';

/**
 * The case conversation between the insured and the analyst. `insurer` follows the same rule as
 * `ExpedienteService`: only the insured portal sends it, and only when they are a client of more
 * than one company — case ids repeat across schemas.
 */
@Injectable({ providedIn: 'root' })
export class CaseMessagesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/cases`;

  /** Reading does NOT mark as read: that is `markRead`, called when someone actually looks. */
  thread(caseId: number, insurer?: string | null): Observable<CaseMessageThread> {
    return this.http.get<CaseMessageThread>(`${this.base}/${caseId}/messages`, this.options(insurer));
  }

  post(caseId: number, body: string, insurer?: string | null): Observable<CaseMessage> {
    return this.http.post<CaseMessage>(
      `${this.base}/${caseId}/messages`,
      { body },
      this.options(insurer),
    );
  }

  markRead(caseId: number, insurer?: string | null): Observable<void> {
    return this.http.post<void>(`${this.base}/${caseId}/messages/read`, {}, this.options(insurer));
  }

  private options(insurer?: string | null) {
    return insurer ? { params: new HttpParams().set('insurer', insurer) } : {};
  }
}
