import { formatDate, formatDateTime } from './datetime';

/**
 * Regresión del bug que mostraba la fecha de una denuncia policial un día ANTES del siniestro que
 * denunciaba: `new Date('2026-08-20')` se parsea como UTC por norma, y en Argentina (UTC−3) eso
 * cae el 19/08 a las 21:00. El dato guardado siempre estuvo bien; lo que mentía era la pantalla.
 */
describe('datetime', () => {
  describe('formatDate', () => {
    it('no corre el día con una fecha sin hora', () => {
      expect(formatDate('2026-08-20')).toBe('20/8/2026');
    });

    it('convierte a hora local un instante con zona', () => {
      // 02:00 UTC del 21 es todavía el 20 a las 23:00 en Argentina.
      expect(formatDate('2026-08-21T02:00:00Z')).toBe('20/8/2026');
    });

    it('devuelve el fallback sin valor', () => {
      expect(formatDate(null)).toBe('—');
      expect(formatDate(undefined, 'sin fecha')).toBe('sin fecha');
    });
  });

  describe('formatDateTime', () => {
    it('con una fecha sin hora muestra solo la fecha, sin inventar 00:00', () => {
      expect(formatDateTime('2026-08-20')).toBe('20/8/2026');
    });

    it('mantiene la hora en reloj de 24 h', () => {
      const formatted = formatDateTime('2026-08-20T21:40:00-03:00');
      expect(formatted).toContain('20/08/2026');
      expect(formatted).toContain('21:40');
    });
  });
});
