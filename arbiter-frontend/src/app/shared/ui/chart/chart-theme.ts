import { StatusTone } from '../../../core/models/status-tone';

/**
 * Los colores y tipografías del design system, leídos en tiempo de ejecución para pasárselos a
 * ECharts.
 *
 * ECharts se configura con objetos JavaScript, no con CSS: no puede resolver `var(--status-ok)`
 * solo. La alternativa sería repetir los hex acá, que es justo lo que el guardrail del proyecto
 * prohíbe fuera de `_tokens.scss` — y además dejaría los gráficos desincronizados la primera vez
 * que alguien ajuste la paleta. Leerlos de `:root` con `getComputedStyle` mantiene una sola fuente
 * de verdad, y el día que exista el modo oscuro o el branding por aseguradora los gráficos lo
 * siguen sin tocar una línea.
 */
export interface ChartTheme {
  /** Texto de ejes y leyendas. */
  text: string;
  /** Texto secundario: etiquetas de eje, notas. */
  muted: string;
  /** Líneas de la grilla y de los ejes. */
  grid: string;
  /** Fondo de los tooltips. */
  surface: string;
  border: string;
  /** Tinta de las series sin significado de estado (volumen por ramo, altas del período). */
  ink: string;
  /** El semáforo, por tono. `neutral` cae en la tinta. */
  status: Record<StatusTone, string>;
  fontFamily: string;
}

export function readChartTheme(): ChartTheme {
  const styles = getComputedStyle(document.documentElement);
  // Las custom properties se resuelven al valor computado, así que una cadena de `var()`
  // (--status-ok → --accent-green → el hex) llega hasta acá ya resuelta.
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

/** Ejes, grilla y tooltip, iguales en todos los gráficos del tablero. */
export function baseChartOptions(theme: ChartTheme): Record<string, unknown> {
  return {
    textStyle: { fontFamily: theme.fontFamily, color: theme.text },
    tooltip: {
      backgroundColor: theme.surface,
      borderColor: theme.border,
      textStyle: { color: theme.text },
      // El puntero cruzado de la grilla sólo estorba en gráficos de pocas categorías.
      confine: true,
    },
  };
}
