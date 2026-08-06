// Espejo del enum RiskBand de common-lib (ar.edu.utn.frba.arbiter.common.enums.RiskBand).
// Ordenado de menor a mayor riesgo. Los labels (Bajo/Medio/Alto/Crítico) son cosa del front.
export type RiskBand = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export const RISK_BANDS: RiskBand[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

const LABELS: Record<RiskBand, string> = {
  LOW: 'Bajo',
  MEDIUM: 'Medio',
  HIGH: 'Alto',
  CRITICAL: 'Crítico',
};

export function riskBandLabel(band: RiskBand): string {
  return LABELS[band];
}
