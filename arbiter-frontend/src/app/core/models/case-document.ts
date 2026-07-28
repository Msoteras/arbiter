// Documentación adjunta a un expediente. Los tipos son un conjunto cerrado: el backend
// los usa como clave del multipart (POST /cases/{id}/documents) y como columna `type` de
// case_documents, así que el string tiene que coincidir exactamente.

/** Forma de CaseDocumentResponse (cases-service). No trae el contenido, solo metadata. */
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
 * Los 4 tipos que el expediente puede llevar, en el orden en que se muestran.
 * Fuente única: la usan tanto el uploader (app-doc-upload) como la agenda documental
 * de la pestaña "documentación". Si divergen, el analista ve un checklist que no
 * matchea con lo que puede subir.
 */
export const CASE_DOCUMENT_TYPES: readonly CaseDocumentType[] = [
  { type: 'police_report', label: 'Denuncia policial' },
  { type: 'item_photo', label: 'Foto del bien' },
  { type: 'invoice', label: 'Factura de compra' },
  { type: 'quote', label: 'Presupuesto de reparación' },
] as const;

/** Etiqueta legible del tipo; si el backend manda uno desconocido, se muestra crudo. */
export function documentTypeLabel(type: string): string {
  return CASE_DOCUMENT_TYPES.find((t) => t.type === type)?.label ?? type;
}

export function isPreviewableImage(contentType: string): boolean {
  return contentType.startsWith('image/');
}

export function isPreviewablePdf(contentType: string): boolean {
  return contentType === 'application/pdf';
}

/** Sufijo corto para la fila (PDF, JPG, PNG…). */
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
