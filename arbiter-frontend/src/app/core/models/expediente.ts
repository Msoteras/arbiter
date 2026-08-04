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
  policyNumber: string;
  description: string;
  eventDate: string;
  eventLocation: string;
  claimedAmount: number | null;
  riskBand: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL' | null;
  /**
   * Análisis forense de imágenes (H0009), analista-only. Null cuando no corrió
   * (Fast Track o expediente sin adjuntos de imagen).
   */
  forensicReport: ImageForensicReport | null;
  analysisClassification: Clasificacion | string;
  analysisConfidence: number;
  analysisDetail: string;
  createdAt: string;
  updatedAt: string;
  /** Solo viene en GET /{id}; en listados es null. */
  statusHistory: StatusTransition[] | null;
}
