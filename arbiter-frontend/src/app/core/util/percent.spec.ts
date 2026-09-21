import { formatRate } from './percent';

describe('formatRate', () => {
  /** The team's call: es-AR writes "25 %", and the app writes it stuck to the sign. */
  it('writes the percentage with no space before the sign', () => {
    expect(formatRate(0.25)).toBe('25%');
    expect(formatRate(1)).toBe('100%');
    expect(formatRate(0)).toBe('0%');
  });

  /** Decimals only where the figure needs them: fraud rates are small enough to round to nothing. */
  it('uses a decimal comma, and only the decimals it was asked for', () => {
    expect(formatRate(0.142857, 1)).toBe('14,3%');
    expect(formatRate(0.0357, 1)).toBe('3,6%');
    expect(formatRate(0.15, 1)).toBe('15%');
    expect(formatRate(0.142857)).toBe('14%');
  });

  /** Unknown is not zero: a period that resolved nothing has no share, it has an unknown one. */
  it('answers the fallback when there is nothing to show', () => {
    expect(formatRate(null)).toBe('—');
    expect(formatRate(undefined)).toBe('—');
    expect(formatRate(Number.NaN)).toBe('—');
    expect(formatRate(null, 0, 'sin datos')).toBe('sin datos');
  });
});
