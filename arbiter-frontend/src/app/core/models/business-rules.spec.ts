import { withDocumentLabels } from './business-rules';

describe('withDocumentLabels', () => {
  it('replaces the document code with its Spanish label', () => {
    expect(withDocumentLabels('Falta documento requerido: police_report')).toBe(
      'Falta documento requerido: Denuncia policial',
    );
  });

  it('replaces every occurrence, not just the first', () => {
    expect(withDocumentLabels('Faltan: police_report, item_photo')).toBe(
      'Faltan: Denuncia policial, Foto del bien',
    );
  });

  it('leaves a reason that mentions no document untouched', () => {
    const reason = 'El monto reclamado supera el promedio del ramo';
    expect(withDocumentLabels(reason)).toBe(reason);
  });
});
