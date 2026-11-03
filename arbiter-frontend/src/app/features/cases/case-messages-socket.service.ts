import { Injectable, computed, inject, signal } from '@angular/core';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthSessionService } from '../../core/auth/auth-session.service';
import { CaseMessageEvent } from '../../core/models/case-message';

/**
 * Live delivery over STOMP: a case thread, and the engine board. Receive-only: writing stays on REST.
 *
 * The token goes in the CONNECT frame, not the URL — a query string would end up in nginx's and
 * the platform's access logs.
 */
@Injectable({ providedIn: 'root' })
export class CaseMessagesSocketService {
  private readonly session = inject(AuthSessionService);
  private readonly connections = signal(0);

  /** Some thread is receiving over the socket right now (not just polling). */
  readonly live = computed(() => this.connections() > 0);

  /** Errors stay quiet: the caller still polls, and a socket that cannot connect is not a broken thread. */
  watch<T = CaseMessageEvent>(topic: string): Observable<T> {
    return new Observable<T>((subscriber) => {
      const token = this.session.token();
      if (!token) {
        return;
      }

      let subscription: StompSubscription | undefined;
      // onConnect fires again on every reconnect: count each client once.
      let counted = false;
      const setCounted = (value: boolean) => {
        if (counted === value) return;
        counted = value;
        this.connections.update((n) => n + (value ? 1 : -1));
      };
      const client = new Client({
        brokerURL: this.brokerUrl(),
        connectHeaders: { Authorization: `Bearer ${token}` },
        reconnectDelay: 5000,
        heartbeatIncoming: 20000,
        heartbeatOutgoing: 20000,
        onConnect: () => {
          setCounted(true);
          subscription = client.subscribe(topic, (frame: IMessage) => {
            subscriber.next(JSON.parse(frame.body) as T);
          });
        },
        onWebSocketClose: () => setCounted(false),
      });
      client.activate();

      return () => {
        setCounted(false);
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
