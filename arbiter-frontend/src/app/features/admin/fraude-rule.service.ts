import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

/**
 * La política de antecedentes de fraude tal como la persiste rules-service — calca
 * FraudRecordRuleDto campo por campo. Una fila de `insurer_rule` de tipo FRAUD_RECORD, de toda la
 * aseguradora: el antecedente es de la persona, no de la cobertura que afectó.
 *
 * Acá NO está si el antecedente suma al nivel de riesgo: eso se configura en el scoring, junto al
 * resto de los factores (`fraud_history` y su peso). Tenerlo también acá dejaba que las dos
 * pantallas se contradijeran.
 */
export interface FraudRecordRule {
  /** null mientras la aseguradora nunca la configuró: ventana por defecto y nada vetado. */
  ruleId: number | null;
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
