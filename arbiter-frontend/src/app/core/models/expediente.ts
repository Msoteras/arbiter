import { Clasificacion } from './clasificacion';

// Espejo de CaseResponse del cases-service (GET /api/v1/cases/{id})
export interface ExpedienteResponse {
  id: number;
  status: string;
  policyNumber: string;
  insuredId: string;
  analysisClassification: Clasificacion | string;
  analysisConfidence: number; // 0..1
  analysisDetail: string;
}
