// Mirrors common-lib's ImageForensicReport. Analyst-only: the insured never receives it.

export interface ImageForensicInternalMatch {
  matchedCaseId: number;
  /** `CaseDocument.type` of the matched attachment, used to look it up for display. */
  matchedDocumentType: string;
  matchedFilename: string;
  /** Cosine similarity in [0,1]. */
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
  /** e.g. "item_photo-0". */
  label: string;
  /** `CaseDocument.type` of the analyzed attachment; unique per case, so it links to the file. */
  documentType: string;
  internalMatches: ImageForensicInternalMatch[];
  /** Null when no web search ran (not needed, failed or disabled). */
  webFinding: ImageForensicWebFinding | null;
}

export interface ImageForensicReport {
  imagesAnalyzed: number;
  webSearchesPerformed: number;
  findings: ImageForensicFinding[];
}

export type ForensicAlertLevel = 'bajo' | 'medio' | 'alto';

/**
 * Display-only severity for `app-severity-label`; the authoritative signal is riskScore/riskBand.
 * Any internal match already passed the backend's similarity floor (0.90 by default), hence the
 * 0.95 cut for 'alto'. Returns null when there is nothing to flag.
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

/** Mirrors the Java `WebFinding.found()`: a derived record method, so Jackson does not serialize it. */
function webFindingFound(web: ImageForensicWebFinding | null): boolean {
  return !!web && (web.fullMatches > 0 || web.partialMatches > 0 || web.pages.length > 0);
}

export function forensicFindingIsClean(finding: ImageForensicFinding): boolean {
  return finding.internalMatches.length === 0 && !webFindingFound(finding.webFinding);
}
