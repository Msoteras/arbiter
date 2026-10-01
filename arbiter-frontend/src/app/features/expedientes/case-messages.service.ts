import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  CaseMessage,
  CaseMessageInboxItem,
  CaseMessageThread,
} from '../../core/models/case-message';

/**
 * The case conversation between the insured and the analyst. `insurer` follows the same rule as
 * `ExpedienteService`: only the insured portal sends it, and only when they are a client of more
 * than one company — case ids repeat across schemas.
 */
@Injectable({ providedIn: 'root' })
export class CaseMessagesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/cases`;

  private readonly _inbox = signal<CaseMessageInboxItem[]>([]);
  readonly inboxItems = this._inbox.asReadonly();
  /** Conversations with something unread, not messages: what the nav badges count. */
  readonly unreadChats = computed(
    () => this._inbox().filter((item) => item.unreadCount > 0).length,
  );

  /** Also feeds `inboxItems`/`unreadChats`, which the nav badges and summary cards read. */
  inbox(): Observable<CaseMessageInboxItem[]> {
    return this.http
      .get<CaseMessageInboxItem[]>(`${this.base}/messages/inbox`)
      .pipe(tap((items) => this._inbox.set(items)));
  }

  inboxItem(caseId: number, insurer?: string | null): CaseMessageInboxItem | null {
    return this._inbox().find((item) => this.matches(item, caseId, insurer)) ?? null;
  }

  /**
   * Drops the badge right away, then re-reads the inbox: a list request that left before the read
   * was saved would otherwise land later and bring the old count back.
   */
  clearUnread(caseId: number, insurer?: string | null): void {
    this.updateItem(caseId, insurer, (item) => ({ ...item, unreadCount: 0 }));
    this.inbox().subscribe({ error: () => undefined });
  }

  recordLatest(caseId: number, insurer: string | null | undefined, message: CaseMessage): void {
    this.updateItem(caseId, insurer, (item) => ({
      ...item,
      lastMessageBody: message.body,
      lastMessageSender: message.sender,
      lastMessageAt: message.createdAt,
    }));
  }

  private updateItem(
    caseId: number,
    insurer: string | null | undefined,
    change: (item: CaseMessageInboxItem) => CaseMessageInboxItem,
  ): void {
    this._inbox.update((items) =>
      items.map((item) => (this.matches(item, caseId, insurer) ? change(item) : item)),
    );
  }

  /** Analyst rows carry no slug; an insured's always do. */
  private matches(item: CaseMessageInboxItem, caseId: number, insurer?: string | null): boolean {
    return (
      item.caseId === caseId && (!item.insurerSlug || !insurer || item.insurerSlug === insurer)
    );
  }

  /** Reading does NOT mark as read: that is `markRead`, called when someone actually looks. */
  thread(caseId: number, insurer?: string | null): Observable<CaseMessageThread> {
    return this.http.get<CaseMessageThread>(
      `${this.base}/${caseId}/messages`,
      this.options(insurer),
    );
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
