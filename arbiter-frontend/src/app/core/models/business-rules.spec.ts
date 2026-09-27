import { conLabelesDeDocumento } from './business-rules';

describe('conLabelesDeDocumento', () => {
  it('replaces the document code with its Spanish label', () => {
    expect(conLabelesDeDocumento('Falta documento requerido: police_report')).toBe(
      'Falta documento requerido: Denuncia policial',
    );
  });

  it('replaces every occurrence, not just the first', () => {
    expect(conLabelesDeDocumento('Faltan: police_report, item_photo')).toBe(
      'Faltan: Denuncia policial, Foto del bien',
    );
  });

  it('leaves a reason that mentions no document untouched', () => {
    const razon = 'El monto reclamado supera el promedio del ramo';
    expect(conLabelesDeDocumento(razon)).toBe(razon);
  });
});
