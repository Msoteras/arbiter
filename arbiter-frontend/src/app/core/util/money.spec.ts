import { formatDeductible, formatMoney } from './money';

describe('money', () => {
  it('formats amounts in pesos, with no cents', () => {
    expect(formatMoney(1234567)).toBe('$ 1.234.567');
    expect(formatMoney(null)).toBe('—');
    expect(formatMoney(undefined, 'sin dato')).toBe('sin dato');
  });

  /** No space before the sign, the same as formatRate: the team reads "10 %" as odd. */
  it('answers both halves of the deductible question, in the app percent style', () => {
    expect(formatDeductible(10000, 10)).toBe('$ 10.000 · 10%');
    expect(formatDeductible(10000, 12.5)).toBe('$ 10.000 · 12,5%');
  });

  /** Each half on its own: the insured was given one of the two when they signed. */
  it('shows whichever half it has', () => {
    expect(formatDeductible(10000, null)).toBe('$ 10.000');
    expect(formatDeductible(null, 10)).toBe('10%');
    expect(formatDeductible(null, null)).toBe('—');
  });
});
