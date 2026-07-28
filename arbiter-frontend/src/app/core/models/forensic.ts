// Espejo de ImageForensicReport (common-lib, ar.edu.utn.frba.arbiter.common.dto).
// Solo lo ve el analista — el asegurado nunca recibe este dato. Null en
// ExpedienteResponse cuando no corrió el análisis (Fast Track, o expediente sin
// imágenes adjuntas).

export interface ImageForensicInternalMatch {
  matchedCaseId: number;
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
   * OJO: pese al nombre, hoy el backend manda acá el `type` del documento (ej.
   * "item_photo"), no el nombre de archivo original — AttachmentDocument no
   * lo tiene. Es el mismo string que `CaseDocument.type`, así se
   * cruza cada finding con su adjunto real (ver ForensicAnalysisComponent).
   */
  filename: string;
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

/**
 * `InternalMatch.matchedFilename` tiene el mismo problema que `ImageFinding.filename`
 * (ver arriba): hoy no es un nombre de archivo real, es el `label` armado por
 * `ImageFraudAnalysisService` con formato `tipo-índice` (ej. "item_photo-0") — porque
 * `ImageEmbeddingService.processAndFindDuplicates` recibe ese mismo label como
 * `originalFilename`. Esta función aproxima el `type` real quitando el sufijo `-N` final,
 * para poder cruzarlo contra `CaseDocument.type` del siniestro coincidente.
 */
export function typeFromMatchedFilename(matchedFilename: string): string {
  return matchedFilename.replace(/-\d+$/, '');
}
