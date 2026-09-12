// Espejo de los DTOs de reports-service (ar.edu.utn.frba.arbiter.reports.dto).
// La aseguradora nunca viaja como parámetro: el backend la resuelve del token.

/** Atajos del selector de período: ventanas de 7, 30 y 90 días terminadas hoy. */
export type MetricsRange = 'WEEK' | 'MONTH' | 'QUARTER';

/** Ancho de cada punto de la línea de tiempo. Lo decide el backend según el largo del período. */
export type TimelineGranularity = 'DAY' | 'WEEK' | 'MONTH';

/**
 * Una barra o porción de una distribución.
 *
 * `label` viene en null donde la dimensión todavía no aplica al expediente: uno que se está
 * clasificando no tiene clasificación, uno que el scoring no alcanzó no tiene banda. Se muestran
 * igual ("Sin clasificar" / "Sin evaluar") porque si no las porciones no suman el total.
 */
export interface MetricCount {
  label: string | null;
  count: number;
}

/** Un punto de la línea de tiempo. La distancia entre las dos series es la cola de trabajo. */
export interface TimelinePoint {
  /** Primer día calendario que cubre el punto (ISO), en la zona horaria de la aseguradora. */
  bucket: string;
  reported: number;
  resolved: number;
}

/**
 * Las tarjetas de arriba. Ojo con las dos poblaciones distintas: `reported*` cuenta lo que se
 * denunció en el período y `resolved*` lo que cerró en él — un siniestro de marzo cerrado en abril
 * entra en una de cada una.
 *
 * Las tasas son fracciones de 0 a 1 (las formatea el pipe `percent`) y vienen en null, no en cero,
 * cuando no hay nada que dividir: una semana sin resoluciones tiene tasa de aprobación desconocida,
 * no del 0%.
 */
export interface MetricsSummary {
  reportedCases: number;
  fastTrackCases: number;
  resolvedCases: number;
  approvedCases: number;
  rejectedCases: number;
  /** Caducados. Quedan fuera de las tasas y del promedio: nadie los decidió. */
  lapsedCases: number;
  approvalRate: number | null;
  rejectionRate: number | null;
  fastTrackRate: number | null;
  /** De la denuncia a la decisión del analista, sobre los expedientes decididos en el período. */
  averageResolutionHours: number | null;
  /**
   * La parte de ese tiempo que el expediente pasó esperando a alguien de afuera de la compañía:
   * documentación del asegurado, el informe de un perito, el equipo en el service técnico.
   *
   * Se separa porque el procedimiento de la aseguradora dice que esas derivaciones INTERRUMPEN el
   * plazo legal para expedirse. Cargarle ese tiempo a la gestión mide algo sobre lo que nadie
   * adentro puede actuar. El tiempo propio es la diferencia entre los dos.
   */
  averageWaitingHours: number | null;
}

/** Los dos recortes que ofrece el tablero además del período. Null = todos. */
export interface MetricsFilter {
  branchId: number | null;
  analystId: number | null;
}

/**
 * Lo que pasó con los siniestros que ENTRARON en el período, seguidos hacia adelante.
 *
 * Ojo: es otra población que `MetricsSummary`. El resumen cuenta lo que CERRÓ en el período,
 * venga de cuando venga; el embudo sigue a los que se DENUNCIARON en el período y pregunta hasta
 * dónde llegó cada uno, aunque haya llegado después. Los dos están bien, responden distinto.
 */
export interface IntakeFunnel {
  reported: number;
  /**
   * Cuántos miró el modelo. Nunca es igual a `reported` si hubo Fast Track: un Fast Track lo
   * decide el motor de reglas y el modelo no corre — la base lo prohíbe explícitamente.
   */
  analyzed: number;
  decided: number;
  /** Fast Track agiliza, no automatiza: el analista decide igual. */
  fastTrack: number;
  /** Todavía sin estado final, a hoy. */
  stillOpen: number;
}

/**
 * Cuántas veces el analista terminó donde apuntaba el modelo. Solo cuentan los expedientes con una
 * recomendación accionable: "requiere revisión manual" y "falta documentación" no dicen nada sobre
 * aprobar o rechazar, así que no hay con qué coincidir.
 *
 * Una coincidencia baja no es un problema a corregir empujando a los analistas hacia el modelo: o
 * el modelo está mal calibrado para esta cartera, o está viendo algo que los analistas descartan.
 */
export interface RecommendationAgreement {
  decided: number;
  agreed: number;
  /** Null cuando no hubo nada con recomendación accionable: desconocido, no cero. */
  rate: number | null;
}

/**
 * El objetivo de resolución de la aseguradora y cómo le fue contra él.
 *
 * Es una **meta de gestión** que fija el referente desde el panel de reglas, no el plazo legal: ese
 * corre por expediente y pasarse es otro problema, más grave. Con `enabled` en false el tablero
 * muestra el tiempo promedio sin compararlo — lo mismo que si rules-service no respondiera.
 */
export interface ResolutionTarget {
  enabled: boolean;
  targetDays: number | null;
  /** Cuántos de los DECIDIDOS en el período tardaron más que eso. */
  exceeded: number;
}

export interface ClaimMetrics {
  /** Primer día del período, incluido (ISO). */
  from: string;
  /** Último día del período, incluido (ISO). */
  to: string;
  generatedAt: string;
  granularity: TimelineGranularity;
  filter: MetricsFilter;
  funnel: IntakeFunnel;
  summary: MetricsSummary;
  /** El mismo resumen del período inmediatamente anterior, de igual largo. Es lo que convierte
   *  cada número en una dirección. */
  previousSummary: MetricsSummary;
  agreement: RecommendationAgreement;
  resolutionTarget: ResolutionTarget;
  /** Denunciados en el período, por el estado en el que están HOY. */
  byStatus: MetricCount[];
  byBranch: MetricCount[];
  byClassification: MetricCount[];
  byRiskBand: MetricCount[];
  timeline: TimelinePoint[];
}

/**
 * El tiempo promedio de resolución, como se lee en la tarjeta.
 *
 * Cambia de unidad porque los dos extremos son reales: un Fast Track puede cerrarse en horas y un
 * expediente con peritaje tarda semanas. "720 h" no se lee; "30 d" sí. Debajo del día se muestran
 * horas enteras (el decimal ahí no aporta) y de ahí en adelante días con un decimal.
 *
 * Null no es cero: llega en null cuando en el período no se decidió ningún expediente, y entonces
 * el promedio es desconocido, no instantáneo.
 */
export function resolutionTimeLabel(hours: number | null | undefined): string {
  if (hours === null || hours === undefined) {
    return '—';
  }
  return hours < 24 ? `${Math.round(hours)} h` : `${(hours / 24).toFixed(1)} d`;
}

/**
 * La variación de un indicador contra el período anterior, ya resuelta: cuánto cambió y si eso es
 * bueno o malo. Lo segundo no se deduce del signo — que suba el tiempo de resolución es peor, que
 * suba la tasa de aprobación no es ni bueno ni malo —, así que lo decide quien llama.
 */
export interface MetricDelta {
  /** Diferencia absoluta contra el período anterior. Null si no hay con qué comparar. */
  value: number | null;
  direction: 'up' | 'down' | 'flat';
}

export function delta(current: number | null, previous: number | null): MetricDelta {
  if (current === null || previous === null) {
    return { value: null, direction: 'flat' };
  }
  const difference = current - previous;
  const rounded = Math.abs(difference) < 0.0001 ? 0 : difference;
  return { value: rounded, direction: rounded === 0 ? 'flat' : rounded > 0 ? 'up' : 'down' };
}
