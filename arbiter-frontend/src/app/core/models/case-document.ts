/** Mirrors CaseDocumentResponse: metadata only, no content. */
export interface CaseDocument {
  id: number;
  type: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  uploadedAt: string;
  /** What the analyst asked for, for a document the catalog doesn't name (`other_<n>`). */
  description: string | null;
}

/** Mirrors PendingDocumentResponse: a document the insured still has to upload. */
export interface PendingDocument {
  type: string;
  /** Only for `other_<n>`; the catalog names the rest. */
  description: string | null;
}

/** One item of a documentation request: a catalog code, or `other` with its two texts. */
export interface DocumentRequestItem {
  type: string;
  description?: string;
  reason?: string;
}

/** Why the system suggests a document: the schedule asks every case, or from the case's band up. */
export type DocumentSuggestion = 'SCHEDULE' | 'RISK';

/** A branch document the analyst can still ask for; mirrors RequestableDocumentResponse. */
export interface RequestableDocument {
  code: string;
  name: string;
  /** Null when the system doesn't suggest it. */
  suggestion: DocumentSuggestion | null;
}

/** Shown next to a suggested document, to the analyst only. */
export const DOCUMENT_SUGGESTION_LABELS: Record<DocumentSuggestion, string> = {
  SCHEDULE: 'Agenda',
  RISK: 'Por riesgo',
};

/** The type an item carries for a document the catalog doesn't name. */
export const OTHER_DOCUMENT = 'other';

/** An upload or checklist slot: `type` is the multipart key and `case_documents.type`. */
export interface CaseDocumentType {
  type: string;
  label: string;
}

export function isPreviewableImage(contentType: string): boolean {
  return contentType.startsWith('image/');
}

export function isPreviewablePdf(contentType: string): boolean {
  return contentType === 'application/pdf';
}

export function documentFormatLabel(contentType: string): string {
  if (isPreviewablePdf(contentType)) return 'PDF';
  if (contentType === 'image/jpeg') return 'JPG';
  if (contentType === 'image/png') return 'PNG';
  return contentType;
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
