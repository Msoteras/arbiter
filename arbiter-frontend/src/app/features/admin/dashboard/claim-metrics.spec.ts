import { providerLabel, resolutionTimeLabel } from './claim-metrics';

describe('resolutionTimeLabel', () => {
  it('sin expedientes decididos, el promedio es desconocido y no cero', () => {
    expect(resolutionTimeLabel(null)).toBe('—');
    expect(resolutionTimeLabel(undefined)).toBe('—');
  });

  it('debajo del día se lee en horas enteras', () => {
    expect(resolutionTimeLabel(0)).toBe('0 h');
    expect(resolutionTimeLabel(3.4)).toBe('3 h');
    expect(resolutionTimeLabel(23.9)).toBe('24 h');
  });

  it('desde un día se lee en días, con un decimal', () => {
    expect(resolutionTimeLabel(24)).toBe('1.0 d');
    expect(resolutionTimeLabel(739.2)).toBe('30.8 d');
  });
});

describe('providerLabel', () => {
  it('traduce los dos tipos de tercero que la app deriva', () => {
    expect(providerLabel('ESTUDIO_LIQUIDADOR')).toBe('Peritaje');
    expect(providerLabel('SERVICIO_TECNICO')).toBe('Servicio técnico');
  });

  it('un tipo que no conoce se muestra crudo, no vacío', () => {
    // A new provider type from cases-service must stay readable without a mapping: an ugly label
    // gets noticed and fixed, an empty one goes unnoticed.
    expect(providerLabel('CRISTALERIA')).toBe('CRISTALERIA');
  });
});
