import {
  addDays,
  formatDate,
  formatDateTime,
  isPoliceReportBeforeEvent,
  isTypedDate,
  todayIso,
} from './datetime';

describe('datetime', () => {
  describe('formatDate', () => {
    it('does not shift the day for a date without time', () => {
      expect(formatDate('2026-08-20')).toBe('20/8/2026');
    });

    it('converts a zoned instant to local time', () => {
      // 02:00 UTC on the 21st is still 23:00 on the 20th in Argentina.
      expect(formatDate('2026-08-21T02:00:00Z')).toBe('20/8/2026');
    });

    it('returns the fallback without a value', () => {
      expect(formatDate(null)).toBe('—');
      expect(formatDate(undefined, 'sin fecha')).toBe('sin fecha');
    });
  });

  describe('formatDateTime', () => {
    it('shows only the date for a date without time, without inventing 00:00', () => {
      expect(formatDateTime('2026-08-20')).toBe('20/8/2026');
    });

    it('keeps a 24-hour clock', () => {
      const formatted = formatDateTime('2026-08-20T21:40:00-03:00');
      expect(formatted).toContain('20/08/2026');
      expect(formatted).toContain('21:40');
    });
  });
});

describe('isTypedDate', () => {
  it('accepts a complete date', () => {
    expect(isTypedDate('2026-06-13')).toBeTrue();
  });

  // <input type="date"> emits these on every keystroke while typing "2026".
  it('rejects the partial values while typing the year', () => {
    expect(isTypedDate('0002-06-13')).toBeFalse();
    expect(isTypedDate('0020-06-13')).toBeFalse();
    expect(isTypedDate('0202-06-13')).toBeFalse();
  });

  it('rejects empty values and formats other than yyyy-MM-dd', () => {
    expect(isTypedDate('')).toBeFalse();
    expect(isTypedDate('13/06/2026')).toBeFalse();
  });
});

describe('isPoliceReportBeforeEvent', () => {
  it('detects a police report on an earlier day', () => {
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '2026-06-12', '')).toBeTrue();
  });

  it('flags nothing when it is later', () => {
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '2026-06-14', '09:00')).toBeFalse();
  });

  it('detects the inversion within the same day', () => {
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '2026-06-13', '08:00')).toBeTrue();
  });

  it('the same day with the report later is not inconsistent', () => {
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '2026-06-13', '22:30')).toBeFalse();
  });

  it('claims nothing on the same day when a time is missing', () => {
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '2026-06-13', '')).toBeFalse();
    expect(isPoliceReportBeforeEvent('2026-06-13', '', '2026-06-13', '08:00')).toBeFalse();
  });

  it('stays quiet while the date is half typed', () => {
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '0202-06-13', '')).toBeFalse();
    expect(isPoliceReportBeforeEvent('2026-06-13', '20:00', '', '')).toBeFalse();
  });
});

describe('todayIso', () => {
  it('uses the local date, not UTC', () => {
    // 22:30 on 13/06 in Argentina (UTC−3) is already 14/06 in UTC.
    const lateNight = new Date(2026, 5, 13, 22, 30);
    expect(todayIso(lateNight)).toBe('2026-06-13');
  });

  it('pads month and day with zeros', () => {
    expect(todayIso(new Date(2026, 0, 5))).toBe('2026-01-05');
  });
});

describe('addDays', () => {
  it('moves days back and forward', () => {
    expect(addDays('2026-06-13', -1)).toBe('2026-06-12');
    expect(addDays('2026-06-13', 1)).toBe('2026-06-14');
  });

  it('crosses month and year ends', () => {
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
    expect(addDays('2026-01-01', -1)).toBe('2025-12-31');
  });

  // The browser's timezone is not guaranteed; DST nights must not drop a day.
  it('does not shift across a clock change', () => {
    expect(addDays('2026-10-18', -1)).toBe('2026-10-17');
    expect(addDays('2026-11-01', -1)).toBe('2026-10-31');
  });

  it('returns empty for a half-typed date', () => {
    expect(addDays('0202-06-13', -1)).toBe('');
    expect(addDays('', -1)).toBe('');
  });
});
