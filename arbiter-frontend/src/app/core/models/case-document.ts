// Espejo de CaseDocumentResponse (cases-service). Metadata de un adjunto —
// GET /cases/{id}/documents/{documentId} trae el binario.
export interface CaseDocumentResponse {
  id: number;
  /** Qué ES el documento: police_report, invoice, item_photo, etc. */
  type: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  uploadedAt: string;
}
