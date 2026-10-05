/**
 * Remembers the last report tab, deliberately NOT the filters (those reset to the current month).
 * localStorage rather than sessionStorage so it survives closing the browser.
 */
const STORAGE_KEY = 'arbiter.reports.tab';

/** Child routes of `insurer/reports`. */
export const REPORTS_TABS = ['resolutions', 'fraud'] as const;

export type ReportsTab = (typeof REPORTS_TABS)[number];

export const DEFAULT_REPORTS_TAB: ReportsTab = 'resolutions';

export function rememberReportsTab(tab: string | undefined): void {
  if (!isReportsTab(tab)) {
    return;
  }
  try {
    localStorage.setItem(STORAGE_KEY, tab);
  } catch {
    // Private mode or blocked storage: no memory, but the screen still opens.
  }
}

/** Validates what it reads: `localStorage` can hold anything, which would be a nonexistent route. */
export function rememberedReportsTab(): ReportsTab {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return isReportsTab(stored) ? stored : DEFAULT_REPORTS_TAB;
  } catch {
    return DEFAULT_REPORTS_TAB;
  }
}

function isReportsTab(value: string | null | undefined): value is ReportsTab {
  return REPORTS_TABS.includes(value as ReportsTab);
}
