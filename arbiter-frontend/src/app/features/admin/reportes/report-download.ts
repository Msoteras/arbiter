import { HttpErrorResponse } from '@angular/common/http';

/** Saves a report file the backend generated. Both tabs export, and both export the same way. */
export function downloadReport(document: Document, blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

/** What went wrong, in the referent's terms rather than the status code's. */
export function reportErrorMessage(err: HttpErrorResponse): string {
  switch (err.status) {
    case 400:
      return 'El período no es válido. Revisá las fechas e intentá de nuevo.';
    case 403:
      return 'Tu sesión no tiene acceso a los reportes de esta aseguradora.';
    default:
      return 'No se pudo generar el reporte. Probá de nuevo en unos minutos.';
  }
}
