import { Injectable, inject } from '@angular/core';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthSessionService } from '../../core/auth/auth-session.service';
import { CaseMessageEvent } from '../../core/models/case-message';

/**
 * Live delivery of a case thread over STOMP. Receive-only: writing stays on REST.
 *
 * The token goes in the CONNECT frame, not the URL — a query string would end up in nginx's and
 * the platform's access logs.
 */
@Injectable({ providedIn: 'root' })
export class CaseMessagesSocketService {
  private readonly session = inject(AuthSessionService);

  /** Errors stay quiet: the caller still polls, and a socket that cannot connect is not a broken thread. */
  watch(topic: string): Observable<CaseMessageEvent> {
    return new Observable<CaseMessageEvent>((subscriber) => {
      const token = this.session.token();
      if (!token) {
        return;
      }

      let subscription: StompSubscription | undefined;
      const client = new Client({
        brokerURL: this.brokerUrl(),
        connectHeaders: { Authorization: `Bearer ${token}` },
        reconnectDelay: 5000,
        heartbeatIncoming: 20000,
        heartbeatOutgoing: 20000,
        onConnect: () => {
          subscription = client.subscribe(topic, (frame: IMessage) => {
            subscriber.next(JSON.parse(frame.body) as CaseMessageEvent);
          });
        },
      });
      client.activate();

      return () => {
        subscription?.unsubscribe();
        void client.deactivate();
      };
    });
  }

  private brokerUrl(): string {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
    return `${scheme}://${window.location.host}${environment.apiBaseUrl}/ws`;
  }
}
