import { amountInputLabel, amountInputValue, formatDeductible, formatMoney } from './money';

describe('money', () => {
  it('shows a typed amount with thousands separators and comma cents', () => {
    expect(amountInputLabel('')).toBe('');
    expect(amountInputLabel('720000')).toBe('720.000');
    expect(amountInputLabel('612500.5')).toBe('612.500,5');
    expect(amountInputLabel('612500.')).toBe('612.500,');
  });

  it('keeps only digits and one decimal comma, so a letter like "e" never gets in', () => {
    expect(amountInputValue('')).toBe('');
    expect(amountInputValue('1e5')).toBe('15');
    expect(amountInputValue('$ 612.500,50')).toBe('612500.50');
    expect(amountInputValue('612.500,505')).toBe('612500.50');
    expect(amountInputValue('007')).toBe('7');
    expect(amountInputValue(',5')).toBe('0.5');
  });

  it('round-trips what the label shows', () => {
    for (const value of ['0', '1500', '612500.5', '1300000.25']) {
      expect(amountInputValue(amountInputLabel(value))).toBe(value);
    }
  });

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
