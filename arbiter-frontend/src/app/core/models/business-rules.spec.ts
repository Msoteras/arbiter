import { NAMED_DOCUMENT_TYPES_FIXTURE as TYPES } from '../testing/document-types';
import { documentMentions, withDocumentLabels } from './business-rules';

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

describe('documentMentions', () => {
  const available = new Set(['police_report', 'item_photo']);

  it('links a document named by its code, showing its name', () => {
    expect(documentMentions('Revisar police_report.', TYPES, available)).toEqual([
      { text: 'Revisar ', code: null },
      { text: 'Denuncia policial', code: 'police_report' },
      { text: '.', code: null },
    ]);
  });

  it("links a document named by the model, keeping the model's wording", () => {
    expect(documentMentions('La denuncia policial no coincide', TYPES, available)).toEqual([
      { text: 'La ', code: null },
      { text: 'denuncia policial', code: 'police_report' },
      { text: ' no coincide', code: null },
    ]);
  });

  it("doesn't link a document the case doesn't have", () => {
    expect(documentMentions('Falta: purchase_proof', TYPES, available)).toEqual([
      { text: 'Falta: Comprobante de compra', code: null },
    ]);
  });

  it('handles names with accents and parentheses', () => {
    const segments = documentMentions(
      'Según la Captura de última conexión y la Baja de IMEI (ENACOM)',
      TYPES,
      new Set(['last_connection', 'imei_deregistration']),
    );
    expect(segments.filter((s) => s.code).map((s) => s.code)).toEqual([
      'last_connection',
      'imei_deregistration',
    ]);
  });

  it("doesn't match a name inside a longer word", () => {
    expect(documentMentions('Foto del bienestar', TYPES, available)).toEqual([
      { text: 'Foto del bienestar', code: null },
    ]);
  });

  it('leaves a reason that names no document as a single piece', () => {
    const reason = 'El monto reclamado supera el promedio del ramo';
    expect(documentMentions(reason, TYPES, available)).toEqual([{ text: reason, code: null }]);
  });
});
