import { Clasificacion } from './clasificacion';

// Espejo de CaseResponse del cases-service (GET /api/v1/cases/{id})
export interface ExpedienteResponse {
  id: number;
  status: string;
  branch: string;
  product: string;
  claimCause: string;
  insuredItem: string;
  insuredId: string;
  policyNumber: string;
  description: string;
  eventDate: string;
  eventLocation: string;
  claimedAmount: number | null;
  analysisClassification: Clasificacion | string;
  analysisConfidence: number;
  analysisDetail: string;
}
