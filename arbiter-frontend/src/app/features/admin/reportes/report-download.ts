import { HttpErrorResponse } from '@angular/common/http';

export interface ReportFile {
  blob: Blob;
  filename: string;
}

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

export function reportErrorMessage(err: HttpErrorResponse): string {
  switch (err.status) {
    case 400:
      // A refused period, or a branch that no longer exists (stale link): either way, the filters.
      return 'Los filtros no son válidos. Revisá el período y el ramo e intentá de nuevo.';
    case 403:
      return 'Tu sesión no tiene acceso a los reportes de esta aseguradora.';
    default:
      return 'No se pudo generar el reporte. Probá de nuevo en unos minutos.';
  }
}
