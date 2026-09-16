import { RiskBand } from '../../../core/models/risk-band';

/**
 * Una barra de las distribuciones de este reporte. No reusa el `MetricCount` de
 * `resolution-report.ts` porque acá el label es realmente nulo: un expediente sobre el que el
 * scoring nunca corrió no tiene banda, y el backend lo manda así a propósito (ver el javadoc de
 * `MetricCount` en reports-service). El del reporte de resolución nunca ve un null — sus buckets
 * son estados y hechos generadores, que siempre están.
 */
export interface FraudBucket {
  label: string | null;
  count: number;
}

/**
 * Espejo de los DTOs de reports-service (FraudReport / FraudReportRow / FraudSummary).
 * Los enums llegan como literales; pasarlos a español es cosa del frontend.
 */

/** Espejo de FraudSignal: por qué un expediente aparece en el reporte. */
export type FraudSignal = 'HIGH_RISK_SCORE' | 'REPEAT_CLAIMANT' | 'FORENSIC_INCONSISTENCY';

export interface FraudReportRow {
  caseId: number;
  insuredName: string;
  insuredDni: string;
  branch: string;
  claimCause: string;
  reportedAt: string;
  /**
   * El nivel de alerta. Null cuando el scoring nunca corrió sobre el expediente (Fast Track, o
   * todavía clasificándose): sigue listado si otra señal se disparó, y el gauge lo muestra como
   * "Sin evaluar" en vez de como riesgo bajo, que es lo contrario de lo que pasa.
   */
  riskBand: RiskBand | null;
  signals: FraudSignal[];
  /** Denuncias del asegurado en los 12 meses hasta ésta, ésta incluida: 1 = no hubo otras. */
  claimsInWindow: number;
  suspiciousImages: number;
  /** Literal de CaseStatus. */
  status: string;
  /** Decisión humana, no banda del modelo: el score sugiere, el analista determina. */
  fraudDetermined: boolean;
  expertBacked: boolean;
}

export interface FraudSummary {
  /**
   * Todas las denuncias del período y ramo, marcadas o no: el denominador de las dos tasas. Va como
   * cifra propia porque "8 expedientes con indicios" no dice nada hasta saber si es 8 sobre 20 o
   * sobre 2000. No lo mueve el filtro de nivel de alerta — con "Crítico" puesto, la tasa sigue
   * respondiendo qué parte del período es crítica.
   */
  totalClaims: number;
  flagged: number;
  /** `flagged / totalClaims`, fracción 0..1; null cuando no hay contra qué dividir. */
  flaggedRate: number | null;
  /** Con dos o más señales cruzadas — el motivo por el que existe el reporte. */
  multiSignal: number;
  fraudDetermined: number;
  /**
   * `fraudDetermined / totalClaims`. **Rezaga a propósito**: la población son las denuncias del
   * período, y las más recientes siguen abiertas, así que el mes en curso lee bajo y sube a medida
   * que esos expedientes cierran.
   */
  fraudRate: number | null;
  backedByExpert: number;
  /** Los buckets de {@link alertLevelLabel}: `CRITICAL`, `HIGH`, `NOT_FLAGGED` y `NOT_SCORED`. */
  byAlertLevel: FraudBucket[];
  /** Los buckets se superponen: un expediente con dos señales cuenta en las dos. */
  bySignal: FraudBucket[];
}

export interface FraudReport {
  from: string;
  to: string;
  branch: string | null;
  riskBand: RiskBand | null;
  generatedAt: string;
  summary: FraudSummary;
  rows: FraudReportRow[];
}

export interface FraudReportParams {
  from: string;
  to: string;
  branchId: number | null;
  /** Vacío = todas las bandas. */
  riskBand: string;
}

/** Banda → segmento del `app-fraud-gauge` (1..4, de Bajo a Crítico). */
const GAUGE_BANDS: Record<RiskBand, 1 | 2 | 3 | 4> = {
  LOW: 1,
  MEDIUM: 2,
  HIGH: 3,
  CRITICAL: 4,
};

/**
 * El gauge solo se dibuja cuando el score efectivamente alertó, y por eso recibe la fila entera y
 * no la banda: un score bajo NO es un índice de fraude, así que pintarlo bajo el título "Nivel de
 * alerta" leería como "no hay nada acá" justo en un expediente que está listado porque otra señal
 * sí encontró algo. Para esos casos devuelve null y el gauge muestra {@link alertEmptyLabel}.
 */
export function riskGaugeBand(row: FraudReportRow): 1 | 2 | 3 | 4 | null {
  return scoreAlerted(row) && row.riskBand !== null ? GAUGE_BANDS[row.riskBand] : null;
}

/** El score es una alerta solo en las dos bandas superiores, que es cuando dispara la señal. */
export function scoreAlerted(row: FraudReportRow): boolean {
  return row.signals.includes('HIGH_RISK_SCORE');
}

/**
 * Qué dice la celda cuando el score no alertó. "Sin evaluar" y "No alertó" no son lo mismo: en el
 * primero el motor nunca corrió (Fast Track, o clasificación fallida), en el segundo corrió y no
 * marcó nada — y eso último es un dato operativo distinto.
 */
export function alertEmptyLabel(row: FraudReportRow): string {
  return row.riskBand === null ? 'Sin evaluar' : 'No alertó';
}

const ALERT_LEVEL_LABELS: Record<string, string> = {
  CRITICAL: 'Crítico',
  HIGH: 'Alto',
  NOT_FLAGGED: 'No alertó',
  NOT_SCORED: 'Sin evaluar',
};

export function alertLevelLabel(bucket: string | null): string {
  return bucket === null ? 'Sin evaluar' : (ALERT_LEVEL_LABELS[bucket] ?? bucket);
}

/**
 * Lo que va en la columna "Indicadores": cada señal con su magnitud, que es lo accionable. "Score
 * de riesgo alto" solo dice que el motor la marcó; "3 denuncias en 12 meses" dice qué mirar.
 */
export function indicators(row: FraudReportRow): string[] {
  return row.signals.map((signal) => {
    switch (signal) {
      case 'HIGH_RISK_SCORE':
        return 'Score de riesgo alto';
      case 'REPEAT_CLAIMANT':
        return `${row.claimsInWindow} denuncias en 12 meses`;
      case 'FORENSIC_INCONSISTENCY':
        return row.suspiciousImages === 1
          ? '1 imagen con coincidencia'
          : `${row.suspiciousImages} imágenes con coincidencia`;
    }
  });
}

const SIGNAL_LABELS: Record<FraudSignal, string> = {
  HIGH_RISK_SCORE: 'Score de riesgo alto',
  REPEAT_CLAIMANT: 'Denuncias repetidas',
  FORENSIC_INCONSISTENCY: 'Incoherencias forenses',
};

export function fraudSignalLabel(signal: string): string {
  return (SIGNAL_LABELS as Record<string, string>)[signal] ?? signal;
}
