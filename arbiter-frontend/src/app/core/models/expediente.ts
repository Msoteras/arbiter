import { Clasificacion } from './clasificacion';
import { ImageForensicReport } from './forensic';

// Espejo de StatusTransitionResponse del cases-service.
// `fromStatus` es null en la fila de creación del expediente.
export interface StatusTransition {
  fromStatus: string | null;
  toStatus: string;
  actor: 'SYSTEM' | 'INSURED' | 'ANALYST';
  reason: string;
  changedAt: string;
}

/**
 * El aporte de un factor al score de fraude — espejo de RiskBreakdownItem del back. Lo muestra la
 * vista del analista para que el score no sea una caja negra: qué factor pesó y por qué.
 */
export interface RiskBreakdownItem {
  factorId: string;
  /** Contribución normalizada del factor en [0,1]. */
  rawScore: number;
  /** Peso de la aseguradora para este factor. */
  weight: number;
  /** rawScore * weight — el empuje absoluto de este factor sobre el total. */
  weightedContribution: number;
  rationale: string;
}

// Espejo de CaseResponse del cases-service (GET /api/v1/cases/{id})
export interface ExpedienteResponse {
  id: number;
  /**
   * De qué aseguradora es. Sólo vienen en "mis siniestros" del asegurado, la única vista que
   * mezcla compañías. `insurerSlug` es lo que se usa para volver a pedir el expediente (`id` se
   * repite entre aseguradoras); `insurerName` es para mostrarlo, porque dos siniestros con el
   * mismo número no se distinguen si no se dice de quién es cada uno.
   */
  insurerSlug?: string | null;
  insurerName?: string | null;
  status: string;
  branch: string;
  product: string;
  claimCause: string;
  insuredItem: string;
  insuredId: string;
  /** Nombre real del asegurado, resuelto por classification-service al clasificar. Null hasta entonces. */
  insuredName: string | null;
  /**
   * Persona políticamente expuesta, según lo declaró el asegurado al denunciar (UIF/PLA). Es
   * debida diligencia, no una señal de fraude: se muestra entre los datos del asegurado para que
   * el analista lo tenga a la vista, y no participa del scoring ni de la clasificación (D16).
   */
  pep: boolean;
  policyNumber: string;
  description: string;
  eventDate: string;
  eventLocation: string;
  claimedAmount: number | null;
  riskBand: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL' | null;
  /** Score de fraude normalizado en [0,1]. Null cuando no se scoreó ("sin scorear"). */
  riskScore: number | null;
  /** Desglose del score por factor (analista-only). Null/[] cuando no se scoreó. */
  riskBreakdown: RiskBreakdownItem[] | null;
  /**
   * Análisis forense de imágenes (H0009), analista-only. Null cuando no corrió
   * (Fast Track o expediente sin adjuntos de imagen).
   */
  forensicReport: ImageForensicReport | null;
  /**
   * Analista dueño del expediente, por su id de analista dentro de la aseguradora (el mismo que
   * devuelve `GET /auth/users/analysts`). Null = sin asignar. No es el id de usuario de la
   * sesión: son tablas distintas.
   */
  assignedAnalystId: number | null;
  /** Nombre del analista asignado, resuelto por el backend. Null = sin asignar. */
  assignedAnalystName: string | null;
  analysisClassification: Clasificacion | string;
  analysisConfidence: number;
  analysisDetail: string;
  createdAt: string;
  updatedAt: string;
  /** Solo viene en GET /{id}; en listados es null. */
  statusHistory: StatusTransition[] | null;
}
