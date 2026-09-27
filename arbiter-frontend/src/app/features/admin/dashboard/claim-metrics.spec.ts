import { providerLabel, resolutionTimeLabel } from './claim-metrics';

describe('resolutionTimeLabel', () => {
  it('with no decided cases the average is unknown, not zero', () => {
    expect(resolutionTimeLabel(null)).toBe('—');
    expect(resolutionTimeLabel(undefined)).toBe('—');
  });

  it('reads in whole hours under a day', () => {
    expect(resolutionTimeLabel(0)).toBe('0 h');
    expect(resolutionTimeLabel(3.4)).toBe('3 h');
    expect(resolutionTimeLabel(23.9)).toBe('24 h');
  });

  it('reads in days with one decimal from a day on', () => {
    expect(resolutionTimeLabel(24)).toBe('1.0 d');
    expect(resolutionTimeLabel(739.2)).toBe('30.8 d');
  });
});

describe('providerLabel', () => {
  it('translates the two kinds of provider the app refers cases to', () => {
    expect(providerLabel('ESTUDIO_LIQUIDADOR')).toBe('Peritaje');
    expect(providerLabel('SERVICIO_TECNICO')).toBe('Servicio técnico');
  });

  it('shows an unknown kind raw, not empty', () => {
    // A new provider type from cases-service must stay readable without a mapping: an ugly label
    // gets noticed and fixed, an empty one goes unnoticed.
    expect(providerLabel('CRISTALERIA')).toBe('CRISTALERIA');
  });
});
