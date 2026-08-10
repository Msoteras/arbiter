// Espejo de ImageForensicReport (common-lib, ar.edu.utn.frba.arbiter.common.dto).
// Solo lo ve el analista — el asegurado nunca recibe este dato. Null en
// ExpedienteResponse cuando no corrió el análisis (Fast Track, o expediente sin
// imágenes adjuntas).

export interface ImageForensicInternalMatch {
  matchedCaseId: number;
  /** `CaseDocument.type` del adjunto que coincidió — con esto se lo busca para mostrarlo. */
  matchedDocumentType: string;
  /** Nombre original del archivo, solo para mostrar. */
  matchedFilename: string;
  /** Similitud coseno en [0,1]. */
  similarity: number;
}

export interface ImageForensicWebPage {
  url: string;
  title: string;
}

export interface ImageForensicWebFinding {
  fullMatches: number;
  partialMatches: number;
  pages: ImageForensicWebPage[];
  bestGuessLabel: string;
}

export interface ImageForensicFinding {
  /** Ej. "item_photo-0". */
  label: string;
  /**
   * `CaseDocument.type` del adjunto analizado (ej. "item_photo"). Es único por
   * expediente, así se cruza cada finding con su adjunto real (ver
   * ForensicAnalysisComponent).
   */
  documentType: string;
  internalMatches: ImageForensicInternalMatch[];
  /** Null cuando no se buscó en la web (no hizo falta o falló/deshabilitado). */
  webFinding: ImageForensicWebFinding | null;
}

export interface ImageForensicReport {
  imagesAnalyzed: number;
  webSearchesPerformed: number;
  findings: ImageForensicFinding[];
}

export type ForensicAlertLevel = 'bajo' | 'medio' | 'alto';

/**
 * Categorización visual (bajo/medio/alto) de un finding para `app-severity-label`.
 * Es una lectura de presentación, no la señal de riesgo autoritativa: esa vive en
 * riskScore/riskBand (ImageReuseEvaluator/ImageWebMatchEvaluator en classification-service
 * ya alimentan el score general, que es lo que efectivamente marca el expediente para
 * revisión — ver fraud-gauge en el tab Resumen). Devuelve null cuando el hallazgo no
 * tiene nada para alertar (sin matches internos ni web).
 *
 * Umbrales: cualquier coincidencia interna ya superó el piso de similitud del backend
 * (0.90 por defecto) — de ahí el corte en 0.95 para 'alto'. En web, una copia exacta es
 * 'alto'; solo parciales es 'medio'.
 */
export function forensicAlertLevel(finding: ImageForensicFinding): ForensicAlertLevel | null {
  const maxInternalSimilarity = finding.internalMatches.reduce(
    (max, m) => Math.max(max, m.similarity),
    0,
  );
  if (maxInternalSimilarity >= 0.95) return 'alto';
  if (maxInternalSimilarity > 0) return 'medio';

  const web = finding.webFinding;
  if (web?.fullMatches) return 'alto';
  if (web?.partialMatches) return 'medio';
  if (webFindingFound(web)) return 'bajo';

  return null;
}

/**
 * Espejo de `ImageForensicReport.WebFinding.found()` (Java) — es un método derivado del
 * record, no un campo, así que Jackson no lo manda por JSON; hay que recalcularlo acá.
 */
function webFindingFound(web: ImageForensicWebFinding | null): boolean {
  return !!web && (web.fullMatches > 0 || web.partialMatches > 0 || web.pages.length > 0);
}

/** true si el finding no tiene ninguna coincidencia (interna ni web) que mostrar. */
export function forensicFindingIsClean(finding: ImageForensicFinding): boolean {
  return finding.internalMatches.length === 0 && !webFindingFound(finding.webFinding);
}
