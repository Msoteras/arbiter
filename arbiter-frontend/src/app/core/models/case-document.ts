// Documentación adjunta a un expediente. Los tipos son un conjunto cerrado: el backend
// los usa como clave del multipart (POST /cases/{id}/documents) y como columna `type` de
// case_documents, así que el string tiene que coincidir exactamente.

import { DOCUMENT_TYPES } from './business-rules';

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
 * Tipos de documento que el expediente puede llevar. **Fuente única: la agenda documental del
 * referente** (`DOCUMENT_TYPES` en business-rules.ts): lo que el asegurado puede subir es
 * exactamente el vocabulario que el referente administra como requerido. Antes esta lista tenía
 * su propio set (`invoice`/`quote`) que no cruzaba con el del referente (`purchase_proof`/
 * `imei_deregistration`/`last_connection`), así que un documento exigido no se podía subir. Al
 * derivar de una sola fuente, el uploader (app-doc-upload), el wizard de denuncia y el checklist
 * del analista hablan el mismo idioma que la configuración de reglas.
 */
export const CASE_DOCUMENT_TYPES: readonly CaseDocumentType[] = DOCUMENT_TYPES.map((d) => ({
  type: d.code,
  label: d.label,
}));

/**
 * Tipos que el expediente puede llevar SIN estar en la agenda documental: no los exige el referente
 * ni los sube el asegurado, los deja el propio flujo del caso. Hoy el único es el informe que el
 * analista carga cuando vuelve el perito (`expert_report`, ver ExpertAssessmentService). Van
 * aparte de `CASE_DOCUMENT_TYPES` a propósito: sumarlos ahí los volvería casilleros del checklist
 * y opciones del uploader del asegurado, que es justo lo que no son.
 */
export const NON_AGENDA_DOCUMENT_TYPES: readonly CaseDocumentType[] = [
  { type: 'expert_report', label: 'Informe pericial' },
];

/** Etiqueta legible del tipo; si el backend manda uno desconocido, se muestra crudo. */
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
