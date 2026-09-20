/**
 * Which of the two report tabs to open when somebody enters Reportes without naming one.
 *
 * <p>Se recuerda la solapa y NO los filtros, y la diferencia es deliberada: la solapa es con qué
 * reporte trabaja cada uno —el referente vive en Resolución, quien mira fraude vive en Fraude—,
 * mientras que el período es la pregunta de hoy y arranca en el mes en curso (ver
 * {@link ReportFiltersStore}).
 *
 * <p>En `localStorage` y no en `sessionStorage` porque la idea es que sobreviva a cerrar el
 * navegador: si se perdiera con la pestaña, mañana estaríamos igual que hoy. Es una preferencia de
 * pantalla, no un dato de la aseguradora — no hay nada acá que no sea el nombre de una solapa.
 */
const STORAGE_KEY = 'arbiter.reports.tab';

/** Las rutas hijas de `insurer/reports`. Si se agrega una solapa, va acá. */
export const REPORTS_TABS = ['resolutions', 'fraud'] as const;

export type ReportsTab = (typeof REPORTS_TABS)[number];

/** La primera vez, y cuando lo guardado no sirve. */
export const DEFAULT_REPORTS_TAB: ReportsTab = 'resolutions';

/** Anota la solapa abierta. Un valor que no es una solapa se ignora. */
export function rememberReportsTab(tab: string | undefined): void {
  if (!isReportsTab(tab)) {
    return;
  }
  try {
    localStorage.setItem(STORAGE_KEY, tab);
  } catch {
    // Modo privado o storage bloqueado: sin memoria, pero la pantalla abre igual.
  }
}

/**
 * La última solapa usada, o la de siempre. Valida lo leído en vez de confiar: `localStorage` lo
 * edita cualquiera, y un valor cualquiera acá sería una ruta que no existe.
 */
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
