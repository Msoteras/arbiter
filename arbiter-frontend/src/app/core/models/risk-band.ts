// Mirrors common-lib's RiskBand enum, ordered from lowest to highest risk.
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
