import { messageParts } from './message-links';

describe('messageParts', () => {
  function links(body: string): number[] {
    return messageParts(body)
      .filter((part) => part.caseId !== null)
      .map((part) => part.caseId!);
  }

  it('finds a case number written as "#42"', () => {
    expect(messageParts('Mirá el #42, porfa.')).toEqual([
      { text: 'Mirá el ', caseId: null },
      { text: '#42', caseId: 42 },
      { text: ', porfa.', caseId: null },
    ]);
  });

  it('finds "expediente 42" and "siniestro 42", with or without "#", "N°" or "nro."', () => {
    expect(links('Expediente 12, siniestro #13, expediente N° 14 y siniestro nro. 15')).toEqual([
      12, 13, 14, 15,
    ]);
  });

  it('links the whole mention, not only the number', () => {
    expect(messageParts('Va el expediente 42')[1]).toEqual({ text: 'expediente 42', caseId: 42 });
  });

  it('leaves a bare number alone: it can be an amount or a phone', () => {
    expect(links('Me cobraron 42000 y mi teléfono es 1155550000')).toEqual([]);
  });

  it('gives the message back when the parts are joined', () => {
    const body = '#1 al principio,\nexpediente 2 en el medio y #3';
    expect(
      messageParts(body)
        .map((part) => part.text)
        .join(''),
    ).toBe(body);
  });

  it('returns the message as one plain part when it names no case', () => {
    expect(messageParts('Hola')).toEqual([{ text: 'Hola', caseId: null }]);
  });
});
