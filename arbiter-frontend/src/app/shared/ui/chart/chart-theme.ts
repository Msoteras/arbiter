import { StatusTone } from '../../../core/models/status-tone';

/**
 * Design-system colors and fonts read at runtime: ECharts is configured with JS objects and cannot
 * resolve `var(--x)`. Reading them from `:root` avoids duplicating hex values outside `_tokens.scss`.
 */
export interface ChartTheme {
  text: string;
  muted: string;
  grid: string;
  /** Tooltip background. */
  surface: string;
  border: string;
  /** Series without a status meaning (e.g. volume per branch). */
  ink: string;
  status: Record<StatusTone, string>;
  fontFamily: string;
}

export function readChartTheme(): ChartTheme {
  const styles = getComputedStyle(document.documentElement);
  // Custom properties resolve to their computed value, so `var()` chains arrive fully resolved.
  const token = (name: string) => styles.getPropertyValue(name).trim();
  const ink = token('--text-primary');

  return {
    text: ink,
    muted: token('--text-muted'),
    grid: token('--border-subtle'),
    surface: token('--surface'),
    border: token('--border-default'),
    ink,
    status: {
      neutral: token('--text-tertiary'),
      ok: token('--status-ok'),
      warning: token('--status-warning'),
      risk: token('--status-risk'),
      danger: token('--status-danger'),
      info: token('--status-info'),
    },
    fontFamily: token('--font-sans') || 'inherit',
  };
}

export function baseChartOptions(theme: ChartTheme): Record<string, unknown> {
  return {
    textStyle: { fontFamily: theme.fontFamily, color: theme.text },
    tooltip: {
      backgroundColor: theme.surface,
      borderColor: theme.border,
      textStyle: { color: theme.text },
      confine: true,
    },
  };
}
