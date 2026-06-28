import { Clasificacion } from './clasificacion';

/**
 * Espejo EXACTO de ExpedienteResponse del expedientes-service
 * (GET /api/v1/expedientes/{id}). Son los únicos datos que el backend
 * expone hoy por expediente — no agregar campos que el backend no devuelve.
 */
export interface ExpedienteResponse {
  expedienteId: number;
  status: string;
  policyNumber: string;
  insuredId: string;
  analysisClassification: Clasificacion | string;
  analysisConfidence: number; // 0..1
  analysisDetail: string;
}
