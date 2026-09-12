import { resolutionTimeLabel } from './claim-metrics';

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
