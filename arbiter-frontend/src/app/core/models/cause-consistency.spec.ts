import {
  causeConsistencyLabel,
  causeConsistencyTone,
  shouldSurfaceCauseConsistency,
} from './cause-consistency';

describe('cause-consistency', () => {
  it('solo surface el bloque cuando hay algo que mirar', () => {
    expect(shouldSurfaceCauseConsistency('CONTRADICTS')).toBe(true);
    expect(shouldSurfaceCauseConsistency('AMBIGUOUS')).toBe(true);
    expect(shouldSurfaceCauseConsistency('MATCHES')).toBe(false);
  });

  it('trata la ausencia como no evaluado, no como coincidencia', () => {
    expect(shouldSurfaceCauseConsistency(null)).toBe(false);
    expect(shouldSurfaceCauseConsistency(undefined)).toBe(false);
    expect(causeConsistencyTone('')).toBe('neutral');
  });

  it('mapea cada veredicto a su tono del semáforo', () => {
    expect(causeConsistencyTone('MATCHES')).toBe('ok');
    expect(causeConsistencyTone('AMBIGUOUS')).toBe('warning');
    expect(causeConsistencyTone('CONTRADICTS')).toBe('danger');
  });

  it('no rompe si el back suma un valor que el front no conoce', () => {
    expect(causeConsistencyLabel('ALGO_NUEVO')).toBe('ALGO_NUEVO');
    expect(causeConsistencyTone('ALGO_NUEVO')).toBe('neutral');
  });
});
