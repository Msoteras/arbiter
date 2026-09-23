import { conLabelesDeDocumento } from './business-rules';

describe('conLabelesDeDocumento', () => {
  it('cambia el código del documento por su label en castellano', () => {
    expect(conLabelesDeDocumento('Falta documento requerido: police_report')).toBe(
      'Falta documento requerido: Denuncia policial',
    );
  });

  it('cambia todos los que aparezcan, no solo el primero', () => {
    expect(conLabelesDeDocumento('Faltan: police_report, item_photo')).toBe(
      'Faltan: Denuncia policial, Foto del bien',
    );
  });

  it('deja intacta una razón que no menciona documentos', () => {
    const razon = 'El monto reclamado supera el promedio del ramo';
    expect(conLabelesDeDocumento(razon)).toBe(razon);
  });
});
