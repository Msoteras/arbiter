import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { PagedResponse } from '../expedientes/expediente.service';

/** Audit table the entry came from: rules and scoring are versioned separately. */
export type RuleChangeSource = 'INSURER_RULE' | 'SCORING';

/** `null` = the field didn't exist on that side. */
export interface RuleFieldChange {
  field: string;
  previousValue: string | null;
  newValue: string | null;
}

/**
 * The backend pairs each history row with the version that replaced it, because a history row alone
 * only stores what the rule stopped being.
 */
export interface RuleChangeEntry {
  id: string;
  source: RuleChangeSource;
  ruleType: string;
  ruleName: string;
  branchId: number | null;
  branchName: string | null;
  coverageId: number | null;
  coverageName: string | null;
  changedAt: string;
  /** When the replaced version took effect; together with `changedAt` it says how long it lasted. */
  previousValidFrom: string;
  reason: string | null;
  changes: RuleFieldChange[];
  /** The version introduced by this change is the one in force today. */
  current: boolean;
  /** The referente's name, or their email if they have no profile. */
  author: string | null;
  /**
   * The stored version predates tracking whether the rule was active, so `changes` only covers
   * parameters: empty means "not recorded", not "nothing changed", and the view must say which.
   */
  partial: boolean;
}

export interface RuleHistoryParams {
  ruleType?: string;
  branchId?: number;
  /** ISO `yyyy-MM-dd`, both inclusive. */
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

/** `RuleType` labels, plus `SCORING`, which isn't a RuleType: risk scoring has its own table. */
export const RULE_TYPE_LABELS: Record<string, string> = {
  FAST_TRACK: 'Fast Track',
  EXCLUSIONS: 'Exclusiones del ramo',
  BUSINESS_RULES: 'Reglas de negocio',
  COVERAGE_EXCLUSION: 'Exclusiones de la cobertura',
  POLICY_IN_FORCE: 'Vigencia de la póliza',
  WAITING_PERIOD: 'Carencia',
  REPORT_DEADLINE: 'Plazo de denuncia',
  POLICE_DEADLINE: 'Plazo de la denuncia policial',
  MAX_EVENTS_YEAR: 'Tope de eventos por año',
  POLICY_STANDING: 'Mora de la póliza',
  FRAUD_RECORD: 'Antecedente de fraude',
  EXPERT_DERIVATION: 'Derivación a peritaje',
  REPAIR_DERIVATION: 'Derivación a reparación',
  SCORING: 'Puntaje de riesgo',
};

/**
 * Keys are JSON paths of each rule's configuration (rules-service `HardRuleConfig`,
 * `FastTrackConfigDto`, `ScoringConfigDto`…). Unknown keys are shown raw on purpose: a new field
 * must still appear in the history even before it gets a label.
 */
export const RULE_FIELD_LABELS: Record<string, string> = {
  active: 'Regla activa',
  blocksFastTrack: 'Bloquea Fast Track',
  deadlineHours: 'Plazo en horas',
  onArrears: 'Ante mora',
  windowMonths: 'Ventana del antecedente (meses)',
  excludedClaimCauseIds: 'Hechos generadores excluidos',
  includedClaimCauseIds: 'Hechos generadores cubiertos',
  // Last segment of scoring list paths (`factors[image_reuse].weight`); the factor code or band name
  // travels separately as the row qualifier.
  weight: 'Peso',
  factorId: 'Factor',
  band: 'Banda',
  minScoreInclusive: 'Puntaje mínimo de la banda',
  maxClaimedAmountRatio: 'Tope del monto reclamado (sobre la suma asegurada)',
  maxPriorClaims: 'Máximo de siniestros previos',
  priorClaimsWindowMonths: 'Ventana de siniestros previos (meses)',
  minPolicyAgeMonths: 'Antigüedad mínima de la póliza (meses)',
  requiresUpToDatePolicy: 'Exige póliza al día',
  requiredDocumentTypes: 'Documentación exigida',
  criteria: 'Criterios',
  exclusions: 'Exclusiones',
  businessRules: 'Reglas de negocio',
  minClaimedAmount: 'Monto mínimo para derivar a perito',
  claimCauseIds: 'Hechos generadores que admiten reparación',
  enabled: 'Puntaje habilitado',
  fullAnalysisOnFastTrack: 'Análisis completo en Fast Track',
  id: 'Identificador de la configuración',
  // Free-text rules store a bare list with no key, so the backend names it 'configuration'.
  configuration: 'Contenido de la regla',
};

/**
 * Read-only: the append-only history tables are written by rules-service as a side effect of each
 * save. The tenant comes from the JWT.
 */
@Injectable({ providedIn: 'root' })
export class RuleHistoryService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/history`;

  /** Most recent first; the backend fixes the order. */
  find(params: RuleHistoryParams = {}): Observable<PagedResponse<RuleChangeEntry>> {
    let httpParams = new HttpParams();
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== '') {
        httpParams = httpParams.set(key, String(value));
      }
    }
    return this.http.get<PagedResponse<RuleChangeEntry>>(this.base, { params: httpParams });
  }

  /** Only the types the insurer has actually edited at least once. */
  ruleTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/rule-types`);
  }
}
