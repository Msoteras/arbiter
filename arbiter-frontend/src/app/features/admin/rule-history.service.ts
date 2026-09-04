import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { PagedResponse } from '../expedientes/expediente.service';

/** De qué tabla de auditoría salió la entrada. Reglas y scoring versionan por separado. */
export type RuleChangeSource = 'INSURER_RULE' | 'SCORING';

/** Un campo que se movió entre dos versiones de una regla. `null` = el campo no existía de ese lado. */
export interface RuleFieldChange {
  field: string;
  previousValue: string | null;
  newValue: string | null;
}

/**
 * Un cambio de configuración tal como lo devuelve rules-service, ya resuelto: qué regla, de qué
 * valor a cuál, cuándo y por qué. El backend arma el par (versión anterior → versión que la
 * reemplazó) porque una fila del historial sola no alcanza — guarda lo que la regla *dejó* de ser.
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
  /** Instante ISO del cambio. */
  changedAt: string;
  /** Desde cuándo regía la versión reemplazada — con `changedAt` dice cuánto duró. */
  previousValidFrom: string;
  reason: string | null;
  changes: RuleFieldChange[];
  /** La versión que introdujo este cambio es la que rige hoy. */
  current: boolean;
  /**
   * La versión guardada es anterior a que el historial registrara si la regla estaba activa, así
   * que `changes` solo puede hablar de los parámetros. Con estas filas, `changes` vacío significa
   * "no quedó registrado", no "no cambió nada" — y la vista tiene que decir cuál de las dos es.
   */
  partial: boolean;
}

export interface RuleHistoryParams {
  ruleType?: string;
  branchId?: number;
  /** ISO `yyyy-MM-dd`, ambos inclusive. */
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

/**
 * Etiquetas en español de los literales de `RuleType` (+ `SCORING`, que no es un RuleType: el
 * puntaje de riesgo tiene su propia tabla). El backend manda el literal en inglés y traducirlo es
 * responsabilidad del frontend, igual que con el resto de los enums de la plataforma.
 */
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
  SCORING: 'Puntaje de riesgo',
};

/**
 * Etiquetas de los campos que aparecen en un diff. Las claves son rutas JSON de la configuración
 * de cada regla, así que la tabla se lee junto a los DTO de rules-service (`HardRuleConfig`,
 * `FastTrackConfigDto`, `ScoringConfigDto`…). Lo que no esté acá se muestra con la clave cruda:
 * un campo nuevo tiene que verse aunque nadie le haya puesto nombre todavía — dejarlo afuera del
 * historial sería peor que mostrarlo feo.
 */
export const RULE_FIELD_LABELS: Record<string, string> = {
  active: 'Regla activa',
  blocksFastTrack: 'Bloquea Fast Track',
  deadlineHours: 'Plazo en horas',
  onArrears: 'Ante mora',
  windowMonths: 'Ventana del antecedente (meses)',
  excludedClaimCauseIds: 'Hechos generadores excluidos',
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
  enabled: 'Puntaje habilitado',
  fullAnalysisOnFastTrack: 'Análisis completo en Fast Track',
  id: 'Identificador de la configuración',
  // Las reglas de texto libre (exclusiones del ramo, reglas de negocio) guardan una lista pelada:
  // no tiene clave propia, así que el backend la nombra 'configuration'.
  configuration: 'Contenido de la regla',
};

/**
 * Historial de cambios de las reglas de la aseguradora contra rules-service. Solo lectura y sin
 * contraparte de escritura: las dos tablas que lo alimentan son append-only y las escriben los
 * servicios de reglas como efecto de cada guardado. El tenant sale del JWT, así que el referente
 * solo ve el historial de su propia compañía.
 */
@Injectable({ providedIn: 'root' })
export class RuleHistoryService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/rules/history`;

  /** Más reciente primero. El orden lo fija el backend: un historial se lee desde lo último. */
  find(params: RuleHistoryParams = {}): Observable<PagedResponse<RuleChangeEntry>> {
    let httpParams = new HttpParams();
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== '') {
        httpParams = httpParams.set(key, String(value));
      }
    }
    return this.http.get<PagedResponse<RuleChangeEntry>>(this.base, { params: httpParams });
  }

  /** Solo los tipos que la aseguradora efectivamente editó alguna vez. */
  ruleTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/rule-types`);
  }
}
