import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { silentRequest } from '../../core/cache/query-cache';
import { EngineBoard, EngineCase } from '../../core/models/engine';
import { CaseMessagesSocketService } from '../cases/case-messages-socket.service';

/**
 * The engine board: a snapshot over REST, then each step over STOMP. Not cached: it is live by
 * nature, and a stale snapshot would replay steps that already happened.
 */
@Injectable({ providedIn: 'root' })
export class EngineBoardService {
  private readonly http = inject(HttpClient);
  private readonly socket = inject(CaseMessagesSocketService);

  /** `silent` for the periodic resync: nobody is waiting on it, so no activity bar or boot toast. */
  board(silent = false): Observable<EngineBoard> {
    return this.http.get<EngineBoard>(`${environment.apiBaseUrl}/cases/engine`, {
      context: silent ? silentRequest() : undefined,
    });
  }

  /** Every step of every case of the insurer, as the case looks after it. */
  steps(topic: string): Observable<EngineCase> {
    return this.socket.watch<EngineCase>(topic);
  }
}
