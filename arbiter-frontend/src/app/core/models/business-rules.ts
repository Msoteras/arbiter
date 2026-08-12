import { RiskBand } from './risk-band';

// Modelo de configuración de reglas administrado por el referente. Es Ramo-céntrico: el Ramo
// (Celular Protegido, Tecnología Portátil) es la unidad de configuración, y dentro viven las
// coberturas, exclusiones, la agenda documental y el Fast Track (que es POR RAMO).
// El scoring de fraude NO vive acá: es una única config por aseguradora (no por ramo), la
// administra ScoringConfigComponent contra `/api/v1/rules/scoring`. Ver ScoringConfig abajo.
// Refleja la estructura real del producto (manuales + condiciones generales BBVA).

/** Una cobertura del ramo (ej. Robo, Daño por tentativa de robo), con su cláusula y franquicia. */
export interface Coverage {
  id: string;
  name: string;
  /** Código de cláusula de las condiciones generales (ej. "340"). */
  clause: string;
  /** Suma asegurada tope de la cobertura, o null si sale de la póliza. */
  insuredAmount: number | null;
  /** Franquicia como fracción (0..1) del monto, o null. → DER cobertura.franquicia */
  deductibleRatio: number | null;
  /** Plazo para denunciar el siniestro, en días. → DER cobertura.plazo_denuncia_horas */
  reportingWindowDays: number | null;
  /** Máx. de eventos indemnizables por año y póliza. → DER cobertura.tope_eventos_por_anio */
  maxAnnualClaims: number | null;
  /**
   * Carencia: días desde el alta de la póliza en que la cobertura todavía no aplica, aunque la
   * póliza esté vigente. null = sin carencia. → DER cobertura.carencia_dias
   */
  waitingPeriodDays: number | null;
  /** Si la cobertura alcanza al grupo familiar conviviente o solo al titular. */
  coversFamilyGroup: boolean;
  /** Si un siniestro liquidado agota la cobertura para el período. */
  claimExhaustsCoverage: boolean;
  /** Exclusiones específicas de esta cobertura, en texto libre (van al prompt del LLM). */
  exclusions: string[];
  /**
   * Hechos generadores (claim_cause ids) que esta cobertura NO cubre — exclusión DURA que evalúa
   * el motor por código (COVERAGE_EXCLUSION) y audita en rule_result, no el LLM. Opcional: el mock
   * semilla no lo trae; el detalle real de la cobertura lo carga desde rules-service.
   */
  excludedClaimCauseIds?: number[];
}

/** Gate determinístico del Fast Track ("Siniestro Express"), configurado por ramo. */
export interface FastTrackConfig {
  enabled: boolean;
  /** Antigüedad mínima de la póliza, en meses (ej. 6). */
  minPolicyAgeMonths: number | null;
  /** Siniestros previos admitidos dentro de la ventana. */
  maxPriorClaims: number | null;
  /** Ventana para contar los siniestros previos, en meses (ej. 24). */
  priorClaimsWindowMonths: number | null;
  /** Monto reclamado máximo como fracción (0..1) de la suma asegurada. */
  maxClaimedAmountRatio: number | null;
  /** Exige póliza al día. */
  requiresUpToDatePolicy: boolean;
  /** Documentos que el Fast Track exige presentes. */
  requiredDocumentTypes: string[];
  /** Criterios descriptivos (human-readable). */
  criteria: string[];
}

export interface FactorWeight {
  factorId: string;
  weight: number;
}

/** Una banda aplica cuando el score normalizado es >= minScoreInclusive (0..1). */
export interface RiskBandCut {
  band: RiskBand;
  minScoreInclusive: number;
}

/**
 * Scoring de fraude de la aseguradora. Es una config ÚNICA por aseguradora (no por ramo): el
 * backend la persiste en `scoring_configuration` sin `branch_id` y la sirve en
 * `/api/v1/rules/scoring`. La administra ScoringConfigComponent, fuera del master-detail de ramos.
 */
export interface ScoringConfig {
  enabled: boolean;
  /**
   * Si el Fast Track de la aseguradora igual corre el análisis pesado (OCR + fraude de imágenes)
   * para que su score de fraude salga completo. false (default) = Fast Track rápido, score parcial.
   * No vetea el Fast Track — el score es señal paralela; solo decide cuánto análisis corre.
   */
  fullAnalysisOnFastTrack: boolean;
  factors: FactorWeight[];
  bands: RiskBandCut[];
}

/** Configuración completa de un ramo. Unidad que edita el referente. */
export interface RamoRules {
  id: string;
  name: string;
  coverages: Coverage[];
  /** Exclusiones comunes a todas las coberturas del ramo. */
  commonExclusions: string[];
  /** Agenda documental del ramo (códigos de tipo de documento). */
  requiredDocuments: string[];
  /** Reglas de negocio en texto libre. */
  businessRules: string[];
  fastTrack: FastTrackConfig;
}

// ───────────────── Catálogos ─────────────────

/** Factores de riesgo disponibles (ids del contrato RiskFactorIds del back). */
export interface RiskFactorDef {
  id: string;
  label: string;
}

export const RISK_FACTORS: RiskFactorDef[] = [
  { id: 'amount_ratio', label: 'Relación monto reclamado / suma asegurada' },
  { id: 'claim_frequency', label: 'Frecuencia de siniestros del asegurado' },
  { id: 'policy_standing', label: 'Situación de la póliza (vigencia / mora)' },
  { id: 'purchase_to_report_time', label: 'Tiempo entre compra y denuncia' },
  { id: 'document_inconsistency', label: 'Inconsistencias en la documentación' },
  { id: 'image_reuse', label: 'Imagen reutilizada de otra denuncia' },
  { id: 'image_web_match', label: 'Imagen publicada en la web' },
];

export function riskFactorLabel(id: string): string {
  return RISK_FACTORS.find((f) => f.id === id)?.label ?? id;
}

/** Tipos de documento de la agenda documental (mismos códigos que el alta de denuncia). */
export interface DocumentTypeDef {
  code: string;
  label: string;
}

export const DOCUMENT_TYPES: DocumentTypeDef[] = [
  { code: 'police_report', label: 'Denuncia policial' },
  { code: 'purchase_proof', label: 'Comprobante de compra (preexistencia)' },
  { code: 'imei_deregistration', label: 'Baja de IMEI (ENACOM)' },
  { code: 'last_connection', label: 'Captura de última conexión' },
  { code: 'repair_quote', label: 'Presupuesto de reparación' },
  { code: 'item_photo', label: 'Foto del bien' },
];

export function documentTypeLabel(code: string): string {
  return DOCUMENT_TYPES.find((d) => d.code === code)?.label ?? code;
}
