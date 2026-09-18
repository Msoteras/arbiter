import { HttpErrorResponse } from '@angular/common/http';

/** An exported report as the backend sent it. */
export interface ReportFile {
  blob: Blob;
  filename: string;
}

/** Saves a report file the backend generated. Both tabs export, and both export the same way. */
export function downloadReport(document: Document, blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  // Deferred: Firefox and Safari start the download after click() returns, and revoking the URL
  // synchronously can cancel it.
  setTimeout(() => URL.revokeObjectURL(url));
}

/** What went wrong, in the referent's terms rather than the status code's. */
export function reportErrorMessage(err: HttpErrorResponse): string {
  switch (err.status) {
    case 400:
      // A period the backend refused, or a branch that no longer exists (a link from before it
      // was removed): both are the filters, and the filters are right above the message.
      return 'Los filtros no son válidos. Revisá el período y el ramo e intentá de nuevo.';
    case 403:
      return 'Tu sesión no tiene acceso a los reportes de esta aseguradora.';
    default:
      return 'No se pudo generar el reporte. Probá de nuevo en unos minutos.';
  }
}
