/** Mirrors CaseDocumentResponse: metadata only, no content. */
export interface CaseDocument {
  id: number;
  type: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  uploadedAt: string;
}

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
