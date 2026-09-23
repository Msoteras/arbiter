import {
  DEFAULT_REPORTS_TAB,
  rememberReportsTab,
  rememberedReportsTab,
} from './reports-tab-memory';

describe('reports tab memory', () => {
  const KEY = 'arbiter.reports.tab';

  beforeEach(() => localStorage.removeItem(KEY));
  afterAll(() => localStorage.removeItem(KEY));

  it('opens the last tab that was used', () => {
    rememberReportsTab('fraud');

    expect(rememberedReportsTab()).toBe('fraud');
  });

  it('opens the usual one the first time of all', () => {
    expect(rememberedReportsTab()).toBe(DEFAULT_REPORTS_TAB);
  });

  /** localStorage can hold anything; a non-tab value would be a route that doesn't exist. */
  it('ignores a stored value that is not a tab', () => {
    localStorage.setItem(KEY, '../../etc');

    expect(rememberedReportsTab()).toBe(DEFAULT_REPORTS_TAB);
  });

  it('does not store something that is not a tab', () => {
    rememberReportsTab('fraud');
    rememberReportsTab(undefined);
    rememberReportsTab('otra-cosa');

    expect(rememberedReportsTab()).toBe('fraud');
  });

  /** Private mode, or storage blocked: no memory, but the screen still opens. */
  it('falls back to the default when storage throws', () => {
    const getItem = spyOn(Storage.prototype, 'getItem').and.throwError('storage blocked');
    const setItem = spyOn(Storage.prototype, 'setItem').and.throwError('storage blocked');

    expect(() => rememberReportsTab('fraud')).not.toThrow();
    expect(rememberedReportsTab()).toBe(DEFAULT_REPORTS_TAB);

    getItem.and.callThrough();
    setItem.and.callThrough();
  });
});
