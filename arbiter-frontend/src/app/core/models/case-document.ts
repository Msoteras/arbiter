import { DOCUMENT_TYPES, NON_AGENDA_DOCUMENT_TYPES } from './business-rules';

/** Mirrors CaseDocumentResponse: metadata only, no content. */
export interface CaseDocument {
  id: number;
  type: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  uploadedAt: string;
}

export interface CaseDocumentType {
  type: string;
  label: string;
}

/**
 * Derived from `DOCUMENT_TYPES` so uploads use the exact codes the backend expects as multipart
 * key and `case_documents.type`.
 */
export const CASE_DOCUMENT_TYPES: readonly CaseDocumentType[] = DOCUMENT_TYPES.map((d) => ({
  type: d.code,
  label: d.label,
}));

export function documentTypeLabel(type: string): string {
  return (
    CASE_DOCUMENT_TYPES.find((t) => t.type === type)?.label ??
    NON_AGENDA_DOCUMENT_TYPES.find((d) => d.code === type)?.label ??
    type
  );
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
