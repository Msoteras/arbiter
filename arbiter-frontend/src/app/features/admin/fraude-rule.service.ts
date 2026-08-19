import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/**
 * La política de antecedentes de fraude tal como la persiste rules-service — calca
 * FraudRecordRuleDto campo por campo. Una fila de `insurer_rule` de tipo FRAUD_RECORD, de toda la
 * aseguradora: el antecedente es de la persona, no de la cobertura que afectó.
 */
export interface FraudRecordRule {
  /** null mientras la aseguradora nunca la configuró. */
  ruleId: number | null;
  /** false = los antecedentes se registran y se ven igual, pero no puntúan ni vetan nada. */
  enabled: boolean;
  /** Cuánto tiempo sigue contando un antecedente, desde el día en que se registró. */
  windowMonths: number;
  /** Si un antecedente pericial vigente le saca el Fast Track a la denuncia nueva. */
  blocksFastTrack: boolean;
}

/** Mismos límites que valida el backend (@Min(1) @Max(600)). */
export const WINDOW_MONTHS_MIN = 1;
export const WINDOW_MONTHS_MAX = 600;

@Injectable({ providedIn: 'root' })
export class FraudRuleService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/fraud-record-rule`;

  /** La aseguradora que nunca la configuró la recibe apagada, no un 404. */
  get(): Observable<FraudRecordRule> {
    return this.http.get<FraudRecordRule>(this.base);
  }

  save(rule: FraudRecordRule): Observable<FraudRecordRule> {
    return this.http.put<FraudRecordRule>(this.base, rule);
  }
}
