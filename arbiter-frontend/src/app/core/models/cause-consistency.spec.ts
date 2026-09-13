import {
  causeConsistencyLabel,
  causeConsistencyTone,
  shouldSurfaceCauseConsistency,
} from './cause-consistency';

/**
 * El cruce relato ↔ hecho generador declarado. Lo que importa acá no es la redacción sino cuándo
 * el hallazgo llega a la pantalla del analista: el bloque solo aparece si hay algo que mirar, y
 * "no evaluado" (null) nunca se puede leer como "coincide".
 */
describe('cause-consistency', () => {
  it('solo surface el bloque cuando hay algo que mirar', () => {
    expect(shouldSurfaceCauseConsistency('CONTRADICTS')).toBe(true);
    expect(shouldSurfaceCauseConsistency('AMBIGUOUS')).toBe(true);
    // Un MATCHES no dice nada que el expediente no diga ya.
    expect(shouldSurfaceCauseConsistency('MATCHES')).toBe(false);
  });

  /**
   * Fast Track, exclusión dura y las clasificaciones anteriores a este chequeo llegan con null.
   * Mostrarlas como "el relato coincide" le daría al analista una verificación que nadie hizo.
   */
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
