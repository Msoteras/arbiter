import {
  addDays,
  formatDate,
  formatDateTime,
  isPoliceReportBeforeEvent,
  isTypedDate,
  todayIso,
} from './datetime';

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

/**
 * Coherencia entre el siniestro y la denuncia policial, tal como la valida el wizard del alta.
 * Lo que se prueba es cuándo la validación NO tiene que hablar: el bug era que saltaba mientras
 * el asegurado escribía y lo dejaba a mitad de camino de completar la hora.
 */
describe('isTypedDate', () => {
  it('acepta una fecha completa', () => {
    expect(isTypedDate('2026-06-13')).toBeTrue();
  });

  /**
   * Un <input type="date"> emite en cada tecla: escribir "2026" pasa por estos tres valores
   * antes de llegar al bueno, y los tres son fechas válidas anteriores a cualquier siniestro.
   */
  it('rechaza los valores intermedios de tipear el año', () => {
    expect(isTypedDate('0002-06-13')).toBeFalse();
    expect(isTypedDate('0020-06-13')).toBeFalse();
    expect(isTypedDate('0202-06-13')).toBeFalse();
  });

  it('rechaza vacío y formatos que no son yyyy-MM-dd', () => {
    expect(isTypedDate('')).toBeFalse();
    expect(isTypedDate('13/06/2026')).toBeFalse();
  });
});

describe('isPoliceReportBeforeEvent', () => {
  it('detecta la denuncia policial en un día anterior', () => {
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '2026-06-12', '')).toBeTrue();
  });

  it('no marca nada cuando es posterior', () => {
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '2026-06-14', '09:00')).toBeFalse();
  });

  /**
   * El caso que la comparación por fecha sola dejaba pasar: mismo día, pero la denuncia policial
   * a las 08:00 de un siniestro de las 20:00.
   */
  it('detecta la inversión dentro del mismo día', () => {
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '2026-06-13', '08:00')).toBeTrue();
  });

  it('el mismo día con la denuncia después no es incoherencia', () => {
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '2026-06-13', '22:30')).toBeFalse();
  });

  /** Sin las dos horas, el mismo día no alcanza para afirmar nada: la duda no es incoherencia. */
  it('no afirma nada el mismo día si falta alguna hora', () => {
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '2026-06-13', '')).toBeFalse();
    expect(isPoliceReportBeforeEvent('2026-06-13', '', '2026-06-13', '08:00')).toBeFalse();
  });

  /** Lo que motivó todo: no valida mientras la fecha se está escribiendo. */
  it('se calla mientras la fecha está a medio tipear', () => {
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '0202-06-13', '')).toBeFalse();
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '', '')).toBeFalse();
  });
});

describe('todayIso', () => {
  it('usa la fecha local, no la UTC', () => {
    // 22:30 del 13/06 en Argentina (UTC−3) ya es el 14/06 en UTC. La fecha del día es la que ve
    // el asegurado en su reloj, así que tiene que decir 13.
    const lateNight = new Date(2026, 5, 13, 22, 30);
    expect(todayIso(lateNight)).toBe('2026-06-13');
  });

  it('rellena mes y día con cero', () => {
    expect(todayIso(new Date(2026, 0, 5))).toBe('2026-01-05');
  });
});

describe('addDays', () => {
  it('retrocede y avanza días', () => {
    expect(addDays('2026-06-13', -1)).toBe('2026-06-12');
    expect(addDays('2026-06-13', 1)).toBe('2026-06-14');
  });

  it('cruza el fin de mes y el fin de año', () => {
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
    expect(addDays('2026-01-01', -1)).toBe('2025-12-31');
  });

  /**
   * En Argentina el cambio de hora ya no se aplica, pero la app corre en el navegador del
   * asegurado y nada garantiza su zona. Restar 24 h sobre un Date local en una noche de DST cae
   * a las 23:00 del día anterior y se come un día; por eso la cuenta va en UTC.
   */
  it('no se corre en un cambio de hora', () => {
    expect(addDays('2026-10-18', -1)).toBe('2026-10-17');
    expect(addDays('2026-11-01', -1)).toBe('2026-10-31');
  });

  it('devuelve vacío con una fecha a medio tipear', () => {
    expect(addDays('0202-06-13', -1)).toBe('');
    expect(addDays('', -1)).toBe('');
  });
});
