import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { Notification, NotificationsService } from './notifications.service';
import { environment } from '../../../environments/environment';

const base = `${environment.apiBaseUrl}/notifications`;

function notification(read: boolean): Notification {
  return {
    id: 1,
    caseId: 64,
    insurerSlug: 'bbva',
    type: 'AWAITING_DOCUMENTATION',
    content: 'Necesitamos documentación',
    read,
    createdAt: '2026-10-27T21:38:00Z',
    sentAt: null,
  };
}

describe('NotificationsService', () => {
  let service: NotificationsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(NotificationsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function seedCount(count: number): void {
    service.refreshUnreadCount();
    http.expectOne(`${base}/unread-count`).flush(count);
  }

  it('opening the panel clears the badge', () => {
    seedCount(2);
    service.openPanel();
    http.expectOne(base).flush([notification(false), notification(false)]);
    expect(service.unreadCount()).toBe(0);
    http.expectOne(`${base}/read-all`).flush(null);
    http.expectOne(`${base}/unread-count`).flush(0);
  });

  it('marks read when the unread notices are older than the listed ones', () => {
    seedCount(2);
    service.openPanel();
    http.expectOne(base).flush([notification(true)]);
    http.expectOne(`${base}/read-all`).flush(null);
    http.expectOne(`${base}/unread-count`).flush(0);
    expect(service.unreadCount()).toBe(0);
  });

  it('a count requested before opening does not bring the badge back', () => {
    seedCount(2);
    service.refreshUnreadCount();
    const stalePoll = http.expectOne(`${base}/unread-count`);
    service.openPanel();
    http.expectOne(base).flush([notification(false)]);
    stalePoll.flush(2);
    expect(service.unreadCount()).toBe(0);
    http.expectOne(`${base}/read-all`).flush(null);
    http.expectOne(`${base}/unread-count`).flush(0);
  });
});
