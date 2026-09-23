import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/**
 * Mirrors rules-service FraudRecordRuleDto. Insurer-wide: a fraud record belongs to the person, not
 * to a coverage. Whether it adds to the risk level is set in scoring (`fraud_history`), not here.
 */
export interface FraudRecordRule {
  /** null until the insurer configures it: default window, nothing blocked. */
  ruleId: number | null;
  /** How long a record keeps counting, from the day it was registered. */
  windowMonths: number;
  /** Whether an active expert-report record removes Fast Track from a new claim. */
  blocksFastTrack: boolean;
}

/** Same bounds the backend validates (@Min(1) @Max(600)). */
export const WINDOW_MONTHS_MIN = 1;
export const WINDOW_MONTHS_MAX = 600;

@Injectable({ providedIn: 'root' })
export class FraudRuleService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/fraud-record-rule`;

  /** An insurer that never configured it gets the default rule, not a 404. */
  get(): Observable<FraudRecordRule> {
    return this.http.get<FraudRecordRule>(this.base);
  }

  save(rule: FraudRecordRule): Observable<FraudRecordRule> {
    return this.http.put<FraudRecordRule>(this.base, rule);
  }
}
