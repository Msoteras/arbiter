import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, of, tap } from 'rxjs';

import { environment } from '../../../environments/environment';

export interface Notification {
  id: number;
  caseId: number | null;
  /** The CaseStatus that fired it — the panel titles by this. */
  type: string;
  content: string;
  read: boolean;
  /** When the email went out; null if it never did. The table has no creation timestamp. */
  sentAt: string | null;
}

/**
 * Two reads on purpose: the bell only needs the count and is on every screen, so it doesn't pull
 * the list until the panel opens. Every call swallows its error — a failing panel must not break
 * the screen behind it.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/notifications`;

  private readonly _unread = signal(0);
  private readonly _items = signal<Notification[]>([]);
  private readonly _loading = signal(false);

  readonly unreadCount = this._unread.asReadonly();
  readonly items = this._items.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly hasUnread = computed(() => this._unread() > 0);

  refreshUnreadCount(): void {
    this.http
      .get<number>(`${this.baseUrl}/unread-count`)
      .pipe(catchError(() => of(0)))
      .subscribe((count) => this._unread.set(Math.max(0, count)));
  }

  /**
   * Opening the panel means the list was seen, so it also marks read. The counter drops without
   * waiting for the server: a badge that lingers reads as a bug.
   */
  openPanel(): void {
    this._loading.set(true);
    this.http
      .get<Notification[]>(this.baseUrl)
      .pipe(
        catchError(() => of([])),
        tap(() => this._loading.set(false)),
      )
      .subscribe((items) => {
        this._items.set(items);
        if (items.some((item) => !item.read)) {
          this.markAllRead();
        }
      });
  }

  private markAllRead(): void {
    this._unread.set(0);
    this.http
      .post<void>(`${this.baseUrl}/read-all`, {})
      .pipe(catchError(() => of(undefined)))
      .subscribe();
  }

  /** On logout: the next account must not inherit the previous one's badge. */
  clear(): void {
    this._unread.set(0);
    this._items.set([]);
  }
}
