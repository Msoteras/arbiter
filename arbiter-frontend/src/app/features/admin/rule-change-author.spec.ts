import { ruleChangeAuthor } from './rule-change-author';

/**
 * El autor es el único dato del motivo que la vista muestra, y hoy no viaja como campo: sale de
 * adentro del texto. Si esto falla, la auditoría pierde el "quién", que es la mitad de para qué
 * existe.
 */
describe('ruleChangeAuthor', () => {
  it('saca el autor de un motivo en español', () => {
    expect(ruleChangeAuthor('Fast Track actualizado por ana@bbva.com')).toBe('ana@bbva.com');
  });

  /** Los motivos escritos antes de que el backend los normalizara están en inglés. */
  it('saca el autor de un motivo viejo en inglés', () => {
    expect(ruleChangeAuthor('Hard rule POLICE_DEADLINE updated by ana@bbva.com'))
      .toBe('ana@bbva.com');
  });

  it('funciona con un actor que no es un mail', () => {
    expect(ruleChangeAuthor('Hechos generadores cubiertos actualizados por smoke-test-2'))
      .toBe('smoke-test-2');
  });

  /** El "por" del final es el que separa al autor, no uno que aparezca antes en la frase. */
  it('corta por el último separador y no por el primero', () => {
    expect(ruleChangeAuthor('Regla cambiada por pedido del área por ana@bbva.com'))
      .toBe('ana@bbva.com');
  });

  it('devuelve null cuando el motivo no nombra a nadie', () => {
    expect(ruleChangeAuthor('Actualización automática')).toBeNull();
  });

  /** Null y no cadena vacía: la vista no muestra un "por" colgado ni un hueco. */
  it('devuelve null sin motivo o con el autor vacío', () => {
    expect(ruleChangeAuthor(null)).toBeNull();
    expect(ruleChangeAuthor(undefined)).toBeNull();
    expect(ruleChangeAuthor('Fast Track actualizado por ')).toBeNull();
  });
});
