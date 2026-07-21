import { Clasificacion } from './clasificacion';

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
  analysisClassification: Clasificacion | string;
  analysisConfidence: number;
  analysisDetail: string;
  createdAt: string;
  updatedAt: string;
  /** Solo viene en GET /{id}; en listados es null. */
  statusHistory: StatusTransition[] | null;
}
