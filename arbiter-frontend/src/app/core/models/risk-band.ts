import { StatusTone } from './status-tone';

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

/** Same tones as app-fraud-gauge: low ok, medium warning, high risk, critical danger. */
const TONES: Record<RiskBand, StatusTone> = {
  LOW: 'ok',
  MEDIUM: 'warning',
  HIGH: 'risk',
  CRITICAL: 'danger',
};

/** A missing band, or one the frontend doesn't know, is neutral rather than undefined. */
export function riskBandTone(band: RiskBand | null | undefined): StatusTone {
  return (band && TONES[band]) ?? 'neutral';
}
