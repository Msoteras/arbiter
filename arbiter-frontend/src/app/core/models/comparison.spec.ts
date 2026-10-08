import { HttpParams } from '@angular/common/http';

import { parseComparisonMode, withComparison } from './comparison';

describe('withComparison', () => {
  const dates = { from: '2025-01-01', to: '2025-03-31' };

  /** The default is what the backend does on its own, so the request doesn't say it. */
  it('sends nothing for the previous period', () => {
    const params = withComparison(new HttpParams(), { mode: 'PREVIOUS_PERIOD', ...dates });

    expect(params.keys()).toEqual([]);
  });

  /** The backend rejects dates that come with any mode but CUSTOM. */
  it('sends the mode alone when the backend works the dates out', () => {
    const params = withComparison(new HttpParams(), { mode: 'SAME_PERIOD_LAST_YEAR', ...dates });

    expect(params.get('compare')).toBe('SAME_PERIOD_LAST_YEAR');
    expect(params.has('compareFrom')).toBeFalse();
  });

  it('sends the typed range with a custom comparison', () => {
    const params = withComparison(new HttpParams(), { mode: 'CUSTOM', ...dates });

    expect(params.get('compare')).toBe('CUSTOM');
    expect(params.get('compareFrom')).toBe('2025-01-01');
    expect(params.get('compareTo')).toBe('2025-03-31');
  });
});

describe('parseComparisonMode', () => {
  it('falls back to the previous period for anything that is not a mode', () => {
    expect(parseComparisonMode('SAME_PERIOD_LAST_YEAR')).toBe('SAME_PERIOD_LAST_YEAR');
    expect(parseComparisonMode('LAST_DECADE')).toBe('PREVIOUS_PERIOD');
    expect(parseComparisonMode(null)).toBe('PREVIOUS_PERIOD');
  });
});
