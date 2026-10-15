import { NAMED_DOCUMENT_TYPES_FIXTURE as TYPES } from '../testing/document-types';
import { withDocumentLabels } from './business-rules';

describe('withDocumentLabels', () => {
  it('replaces the document code with its Spanish label', () => {
    expect(withDocumentLabels('Falta documento requerido: police_report', TYPES)).toBe(
      'Falta documento requerido: Denuncia policial',
    );
  });

  it('replaces every occurrence, not just the first', () => {
    expect(withDocumentLabels('Faltan: police_report, item_photo', TYPES)).toBe(
      'Faltan: Denuncia policial, Foto del bien',
    );
  });

  it('also labels the documents a provider answers a referral with', () => {
    expect(withDocumentLabels("El documento 'expert_report' y el 'repair_report'", TYPES)).toBe(
      "El documento 'Informe pericial' y el 'Respuesta del servicio técnico'",
    );
  });

  it('leaves a reason that mentions no document untouched', () => {
    const reason = 'El monto reclamado supera el promedio del ramo';
    expect(withDocumentLabels(reason, TYPES)).toBe(reason);
  });

  it('leaves a code it has no name for as it came', () => {
    expect(withDocumentLabels('Falta: police_report', [])).toBe('Falta: police_report');
  });
});
