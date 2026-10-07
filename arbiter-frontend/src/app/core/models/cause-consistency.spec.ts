import {
  causeConsistencyLabel,
  causeConsistencyTone,
  shouldSurfaceCauseConsistency,
} from './cause-consistency';

describe('cause-consistency', () => {
  it('surfaces the block only when there is something to look at', () => {
    expect(shouldSurfaceCauseConsistency('CONTRADICTS')).toBe(true);
    expect(shouldSurfaceCauseConsistency('AMBIGUOUS')).toBe(true);
    expect(shouldSurfaceCauseConsistency('MATCHES')).toBe(false);
  });

  it('treats a missing value as not evaluated, not as a match', () => {
    expect(shouldSurfaceCauseConsistency(null)).toBe(false);
    expect(shouldSurfaceCauseConsistency(undefined)).toBe(false);
    expect(causeConsistencyTone('')).toBe('neutral');
  });

  it('maps each verdict to its status tone', () => {
    expect(causeConsistencyTone('MATCHES')).toBe('ok');
    expect(causeConsistencyTone('AMBIGUOUS')).toBe('warning');
    expect(causeConsistencyTone('CONTRADICTS')).toBe('danger');
  });

  it('does not break when the backend adds an unknown value', () => {
    expect(causeConsistencyLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(causeConsistencyTone('SOMETHING_NEW')).toBe('neutral');
  });
});
