import { DOCUMENT_TYPES } from './business-rules';

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

/**
 * Produced by the case flow itself, not required nor uploaded by the insured. Kept apart from
 * `CASE_DOCUMENT_TYPES` so they never become checklist slots or insured upload options.
 */
export const NON_AGENDA_DOCUMENT_TYPES: readonly CaseDocumentType[] = [
  { type: 'expert_report', label: 'Informe pericial' },
  { type: 'repair_report', label: 'Respuesta del servicio técnico' },
];

export function documentTypeLabel(type: string): string {
  return (
    CASE_DOCUMENT_TYPES.find((t) => t.type === type)?.label ??
    NON_AGENDA_DOCUMENT_TYPES.find((t) => t.type === type)?.label ??
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
