import { delta, trendText } from './trend';

describe('delta', () => {
  it('answers null when either side is missing', () => {
    expect(delta(null, 10)).toEqual({ value: null, direction: 'flat' });
    expect(delta(10, null)).toEqual({ value: null, direction: 'flat' });
  });

  it('resolves the direction from the sign of the difference', () => {
    expect(delta(12, 10)).toEqual({ value: 2, direction: 'up' });
    expect(delta(8, 10)).toEqual({ value: -2, direction: 'down' });
    expect(delta(10, 10)).toEqual({ value: 0, direction: 'flat' });
  });

  /** Floating point noise ("0.30000000000000004") must not read as a real change. */
  it('treats a negligible difference as flat', () => {
    expect(delta(0.1 + 0.2, 0.3)).toEqual({ value: 0, direction: 'flat' });
  });
});

describe('trendText', () => {
  const count = (size: number) => `${size}`;

  it('writes the arrow, the formatted amount and the neutral tail when nothing is "good"', () => {
    expect(trendText({ current: 26, previous: 21, format: count, good: 'neither' })).toBe(
      '▲ 5 vs. el período anterior',
    );
  });

  /** Going up isn't always good news — it depends which direction the caller says is the win. */
  it('reads the direction against "good" to say better or worse', () => {
    expect(trendText({ current: 12, previous: 10, format: count, good: 'up' })).toBe(
      '▲ 2 mejor que el período anterior',
    );
    expect(trendText({ current: 8, previous: 10, format: count, good: 'up' })).toBe(
      '▼ 2 peor que el período anterior',
    );
    expect(trendText({ current: 8, previous: 10, format: count, good: 'down' })).toBe(
      '▼ 2 mejor que el período anterior',
    );
  });

  it('says nothing when there is no previous figure or the change is flat', () => {
    expect(trendText({ current: 10, previous: null, format: count, good: 'up' })).toBe('');
    expect(trendText({ current: 10, previous: 10, format: count, good: 'up' })).toBe('');
  });

  /** Below the minimum base a single case can swing a rate ten points — noise, not a trend. */
  it('says nothing when the previous period is below the minimum base', () => {
    expect(trendText({ current: 0.5, previous: 0.2, format: count, good: 'up', base: 3 })).toBe('');
    expect(trendText({ current: 0.5, previous: 0.2, format: count, good: 'up', base: 5 })).not.toBe(
      '',
    );
  });

  it('lets the caller lower the minimum base', () => {
    expect(
      trendText({ current: 12, previous: 10, format: count, good: 'up', base: 2, minBase: 2 }),
    ).not.toBe('');
  });
});
